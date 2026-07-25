package com.example.merchandisecontrolsplitview.data

import android.content.SharedPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val SHOP_SCOPE_PREFIX = "shop:"
private const val SELECTED_SHOP_PREF_PREFIX = "selected_shop_id:"

data class LinkedShop(
    val shopId: String,
    val code: String?,
    val name: String,
    val role: String?,
    val status: String?,
    val membershipStatus: String? = null,
    val shopStatus: String? = null,
    val selectable: Boolean,
    val canWrite: Boolean
) {
    val displayName: String
        get() = name.ifBlank { code.orEmpty().ifBlank { shopId } }

    val canBeSelected: Boolean
        get() = selectable &&
            !status.isShopStatusDisabled() &&
            !membershipStatus.isShopStatusDisabled() &&
            !shopStatus.isShopStatusDisabled()
}

data class SelectedShop(
    val shopId: String,
    val code: String?,
    val name: String,
    val role: String?,
    val status: String?,
    val canWrite: Boolean
) {
    val displayName: String
        get() = name.ifBlank { code.orEmpty().ifBlank { shopId } }
}

data class ShopContext(
    val ownerUserId: String?,
    val linkedShops: List<LinkedShop>,
    val selectedShop: SelectedShop?,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val syncAllowed: Boolean = true
) {
    val selectableShops: List<LinkedShop>
        get() = linkedShops.filter { it.canBeSelected }

    val activeShopId: String?
        get() = selectedShop?.shopId

    val hasActiveShop: Boolean
        get() = selectedShop != null

    val shouldShowSelector: Boolean
        get() = !isLoading && syncAllowed && selectableShops.size > 1 && selectedShop != null

    companion object {
        fun legacy(ownerUserId: String? = null): ShopContext =
            ShopContext(ownerUserId = ownerUserId, linkedShops = emptyList(), selectedShop = null)

        fun blocked(ownerUserId: String?, message: String?): ShopContext =
            ShopContext(
                ownerUserId = ownerUserId,
                linkedShops = emptyList(),
                selectedShop = null,
                errorMessage = message,
                syncAllowed = false
            )
    }
}

data class ShopContextResolution(
    val context: ShopContext,
    val persistedSelection: String?
)

object ShopContextResolver {
    fun resolve(
        ownerUserId: String?,
        linkedShops: List<LinkedShop>,
        persistedShopId: String?
    ): ShopContextResolution {
        val selectable = linkedShops.filter { it.canBeSelected }
        val selected = selectable.firstOrNull { it.shopId == persistedShopId }
            ?: selectable.firstOrNull()
        val selectedShop = selected?.toSelectedShop()
        return ShopContextResolution(
            context = ShopContext(
                ownerUserId = ownerUserId,
                linkedShops = linkedShops,
                selectedShop = selectedShop
            ),
            persistedSelection = selectedShop?.shopId
        )
    }
}

interface SelectedShopStore {
    fun getSelectedShopId(ownerUserId: String): String?
    fun setSelectedShopId(ownerUserId: String, shopId: String)
    fun clearSelectedShopId(ownerUserId: String)
}

interface LinkedShopRemoteDataSource {
    val isConfigured: Boolean
    suspend fun fetchLinkedShops(): Result<List<LinkedShop>>
}

object EmptyLinkedShopRemoteDataSource : LinkedShopRemoteDataSource {
    override val isConfigured: Boolean = false
    override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> =
        Result.success(emptyList())
}

class SupabaseLinkedShopRemoteDataSource(
    private val client: SupabaseClient?
) : LinkedShopRemoteDataSource {
    override val isConfigured: Boolean get() = client != null

    override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> =
        runCatching {
            val response = (client ?: error("Supabase non configurato"))
                .postgrest
                .rpc("mobile_linked_shops")
                .decodeAs<MobileLinkedShopsResponse>()
            if (!response.ok) error("mobile_linked_shops failed: ${response.code}")
            response.shops.map { it.toLinkedShop() }
        }
}

class ShopContextRepository(
    private val remote: LinkedShopRemoteDataSource,
    private val selectedShopStore: SelectedShopStore,
    private val currentOwnerUserId: () -> String?
) {
    private val refreshLock = Any()
    private var refreshGeneration = 0L
    private val mutableState = MutableStateFlow(ShopContext.legacy())
    val state: StateFlow<ShopContext> = mutableState.asStateFlow()

    suspend fun refresh(ownerUserId: String?) {
        val generation = synchronized(refreshLock) {
            if (currentOwnerUserId() != ownerUserId) return
            refreshGeneration += 1L
            refreshGeneration
        }
        if (ownerUserId.isNullOrBlank() || !remote.isConfigured) {
            synchronized(refreshLock) {
                if (isRefreshCurrentLocked(generation, ownerUserId)) {
                    mutableState.value = ShopContext.legacy(ownerUserId)
                }
            }
            return
        }

        synchronized(refreshLock) {
            if (!isRefreshCurrentLocked(generation, ownerUserId)) return
            val previous = mutableState.value
            mutableState.value = if (previous.ownerUserId == ownerUserId) {
                previous.copy(
                    ownerUserId = ownerUserId,
                    isLoading = true,
                    errorMessage = null,
                    syncAllowed = false
                )
            } else {
                ShopContext(
                    ownerUserId = ownerUserId,
                    linkedShops = emptyList(),
                    selectedShop = null,
                    isLoading = true,
                    syncAllowed = false
                )
            }
        }
        val linkedShopsResult = remote.fetchLinkedShops()
        linkedShopsResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        val linkedShops = linkedShopsResult.getOrNull()
        if (linkedShops == null) {
            synchronized(refreshLock) {
                if (isRefreshCurrentLocked(generation, ownerUserId, requirePublishedOwner = true)) {
                    mutableState.value = ShopContext.blocked(
                        ownerUserId,
                        linkedShopsResult.exceptionOrNull()?.message
                    )
                }
            }
            return
        }
        synchronized(refreshLock) {
            if (!isRefreshCurrentLocked(generation, ownerUserId, requirePublishedOwner = true)) return
            val persisted = selectedShopStore.getSelectedShopId(ownerUserId)
            val resolution = ShopContextResolver.resolve(ownerUserId, linkedShops, persisted)
            resolution.persistedSelection?.let {
                selectedShopStore.setSelectedShopId(ownerUserId, it)
            } ?: selectedShopStore.clearSelectedShopId(ownerUserId)
            mutableState.value = resolution.context
        }
    }

    fun clear() {
        synchronized(refreshLock) {
            refreshGeneration += 1L
            mutableState.value = ShopContext.legacy()
        }
    }

    fun selectShop(shopId: String): Boolean {
        synchronized(refreshLock) {
            val current = mutableState.value
            val ownerUserId = current.ownerUserId ?: return false
            if (currentOwnerUserId() != ownerUserId) return false
            if (current.isLoading || !current.syncAllowed) return false
            val linked = current.selectableShops.firstOrNull { it.shopId == shopId } ?: return false
            val selected = linked.toSelectedShop()
            refreshGeneration += 1L
            selectedShopStore.setSelectedShopId(ownerUserId, selected.shopId)
            mutableState.value = current.copy(selectedShop = selected)
            return true
        }
    }

    private fun isRefreshCurrentLocked(
        generation: Long,
        ownerUserId: String?,
        requirePublishedOwner: Boolean = false
    ): Boolean =
        generation == refreshGeneration &&
            currentOwnerUserId() == ownerUserId &&
            (!requirePublishedOwner || mutableState.value.ownerUserId == ownerUserId)
}

class SharedPreferencesSelectedShopStore(
    private val preferences: SharedPreferences
) : SelectedShopStore {
    override fun getSelectedShopId(ownerUserId: String): String? =
        preferences.getString(key(ownerUserId), null)

    override fun setSelectedShopId(ownerUserId: String, shopId: String) {
        preferences.edit().putString(key(ownerUserId), shopId).apply()
    }

    override fun clearSelectedShopId(ownerUserId: String) {
        preferences.edit().remove(key(ownerUserId)).apply()
    }

    private fun key(ownerUserId: String): String =
        SELECTED_SHOP_PREF_PREFIX + ownerUserId
}

fun LinkedShop.toSelectedShop(): SelectedShop =
    SelectedShop(
        shopId = shopId,
        code = code,
        name = name,
        role = role,
        status = status,
        canWrite = canWrite
    )

fun shopScopedStoreScope(selectedShop: SelectedShop?): String =
    selectedShop?.shopId
        ?.takeIf { it.isNotBlank() }
        ?.let { SHOP_SCOPE_PREFIX + it }
        .orEmpty()

fun shopIdFromStoreScope(storeScope: String?): String? =
    storeScope
        ?.takeIf { it.startsWith(SHOP_SCOPE_PREFIX) }
        ?.removePrefix(SHOP_SCOPE_PREFIX)
        ?.takeIf { it.isNotBlank() }

fun remoteStoreIdFromStoreScope(storeScope: String?): String? =
    shopIdFromStoreScope(storeScope)
        ?: storeScope
            ?.takeIf { it.isNotBlank() }

private fun String?.isShopStatusDisabled(): Boolean =
    when (this?.trim()?.lowercase()) {
        "revoked", "suspended", "disabled", "inactive", "blocked" -> true
        else -> false
    }

@Serializable
private data class MobileLinkedShopsResponse(
    val ok: Boolean = false,
    val code: String = "unknown",
    val shops: List<MobileLinkedShopRow> = emptyList()
)

@Serializable
private data class MobileLinkedShopRow(
    @SerialName("shop_id") val shopId: String,
    @SerialName("shop_code") val shopCode: String? = null,
    @SerialName("shop_name") val shopName: String,
    @SerialName("role_key") val roleKey: String? = null,
    @SerialName("membership_status") val membershipStatus: String? = null,
    @SerialName("shop_status") val shopStatus: String? = null,
    @SerialName("can_select") val canSelect: Boolean = false,
    @SerialName("can_write") val canWrite: Boolean = false
) {
    fun toLinkedShop(): LinkedShop {
        val effectiveStatus = listOf(membershipStatus, shopStatus)
            .firstOrNull { it.isShopStatusDisabled() }
            ?: shopStatus
            ?: membershipStatus
        return LinkedShop(
            shopId = shopId,
            code = shopCode,
            name = shopName,
            role = roleKey,
            status = effectiveStatus,
            membershipStatus = membershipStatus,
            shopStatus = shopStatus,
            selectable = canSelect,
            canWrite = canWrite
        )
    }
}
