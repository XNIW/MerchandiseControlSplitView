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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.jan.supabase.exceptions.RestException

private const val SHOP_SCOPE_PREFIX = "shop:"
private const val SELECTED_SHOP_PREF_PREFIX = "selected_shop_id:"

@Serializable
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

@Serializable
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

@Serializable
data class ShopContext(
    val ownerUserId: String?,
    val linkedShops: List<LinkedShop>,
    val selectedShop: SelectedShop?,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val syncAllowed: Boolean = true,
    /** Last confirmed authorization for this owner/selection; never grants cloud authority. */
    val localAccessAllowed: Boolean = syncAllowed && ownerUserId != null
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
                syncAllowed = false,
                localAccessAllowed = false
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
                selectedShop = selectedShop,
                syncAllowed = linkedShops.isEmpty() || selectedShop != null,
                localAccessAllowed = ownerUserId != null && (linkedShops.isEmpty() || selectedShop != null)
            ),
            persistedSelection = selectedShop?.shopId
        )
    }
}

interface SelectedShopStore {
    fun getSelectedShopId(ownerUserId: String): String?
    fun setSelectedShopId(ownerUserId: String, shopId: String)
    fun clearSelectedShopId(ownerUserId: String)
    fun getConfirmedContext(ownerUserId: String): ShopContext? = null
    fun setConfirmedContext(ownerUserId: String, context: ShopContext) = Unit
    fun clearConfirmedContext(ownerUserId: String) = Unit
    fun isDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String): Boolean = false
    fun setDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String, denied: Boolean) = Unit
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

internal class ShopAccessRejectedException : IllegalStateException("shop_access_rejected")

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
            if (!response.ok) throw ShopAccessRejectedException()
            response.shops.map { it.toLinkedShop() }
        }
}

class ShopContextRepository(
    private val remote: LinkedShopRemoteDataSource,
    private val selectedShopStore: SelectedShopStore,
    private val currentOwnerUserId: () -> String?,
    private val currentDeviceIdentifier: () -> String? = { null }
) {
    private val refreshLock = Any()
    private var refreshGeneration = 0L
    private val mutableState = MutableStateFlow(ShopContext.legacy())
    val state: StateFlow<ShopContext> = mutableState.asStateFlow()

    /** Observation only; does not prevent a selection or grant sync authority. */
    internal fun diagnosticShopEpoch(): Long = synchronized(refreshLock) {
        refreshGeneration
    }

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
            val previous = mutableState.value.takeIf { it.ownerUserId == ownerUserId && it.localAccessAllowed }
                ?: confirmedContext(ownerUserId)
            mutableState.value = applyDeviceDenial(previous ?: ShopContext.blocked(ownerUserId, null)).copy(
                isLoading = true, errorMessage = null, syncAllowed = false)
        }
        val linkedShopsResult = remote.fetchLinkedShops()
        linkedShopsResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        val linkedShops = linkedShopsResult.getOrNull()
        if (linkedShops == null) {
            synchronized(refreshLock) {
                if (isRefreshCurrentLocked(generation, ownerUserId, requirePublishedOwner = true)) {
                    val error = linkedShopsResult.exceptionOrNull()
                    val denied = error is ShopAccessRejectedException || (error is RestException && error.statusCode == 403)
                    if (denied) selectedShopStore.clearConfirmedContext(ownerUserId)
                    val previous = mutableState.value
                    mutableState.value = if (!denied && previous.localAccessAllowed) {
                        previous.copy(isLoading = false, syncAllowed = false, errorMessage = error?.message)
                    } else ShopContext.blocked(ownerUserId, error?.message)
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
            if (resolution.context.localAccessAllowed) selectedShopStore.setConfirmedContext(ownerUserId, resolution.context)
            else selectedShopStore.clearConfirmedContext(ownerUserId)
            mutableState.value = applyDeviceDenial(resolution.context)
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
            mutableState.value = applyDeviceDenial(current.copy(selectedShop = selected, localAccessAllowed = true))
            selectedShopStore.setConfirmedContext(ownerUserId, mutableState.value)
            return true
        }
    }

    /** Small local read before network; no adoption, no cloud permission, no cross-owner fallback. */
    fun restoreLocalAuthorization(ownerUserId: String) {
        synchronized(refreshLock) {
            if (currentOwnerUserId() != ownerUserId) return
            val cached = confirmedContext(ownerUserId) ?: return
            mutableState.value = applyDeviceDenial(cached).copy(isLoading = true, syncAllowed = false, errorMessage = null)
        }
    }

    fun invalidateLocalAuthorization(ownerUserId: String, shopId: String?) {
        synchronized(refreshLock) {
            if (currentOwnerUserId() != ownerUserId || mutableState.value.ownerUserId != ownerUserId ||
                mutableState.value.activeShopId != shopId) return
            refreshGeneration += 1L
            selectedShopStore.clearConfirmedContext(ownerUserId)
            mutableState.value = ShopContext.blocked(ownerUserId, "shop_access_rejected")
        }
    }

    /** Only a successful, freshly scoped device-status RPC may change this durable denial. */
    fun recordDeviceAuthorization(ownerUserId: String, shopId: String?, deviceId: String,
        status: String, canWrite: Boolean) {
        synchronized(refreshLock) {
            val current = mutableState.value
            if (currentOwnerUserId() != ownerUserId || current.ownerUserId != ownerUserId ||
                current.activeShopId != shopId || currentDeviceIdentifier() != deviceId) return
            val denied = status in setOf("revoked", "retired", "suspended")
            val restored = status == "active" && canWrite
            if (!denied && !restored) return
            selectedShopStore.setDeviceDenied(ownerUserId, shopId, deviceId, denied)
            refreshGeneration += 1L
            val membershipAllows = current.linkedShops.isEmpty() ||
                current.selectableShops.any { it.shopId == shopId }
            mutableState.value = current.copy(localAccessAllowed = !denied && membershipAllows,
                errorMessage = if (denied) "device_access_rejected" else null)
        }
    }

    private fun applyDeviceDenial(context: ShopContext): ShopContext {
        val owner = context.ownerUserId ?: return context
        val device = currentDeviceIdentifier() ?: return context
        return if (selectedShopStore.isDeviceDenied(owner, context.activeShopId, device))
            context.copy(localAccessAllowed = false, errorMessage = "device_access_rejected") else context
    }

    private fun confirmedContext(ownerUserId: String): ShopContext? = selectedShopStore.getConfirmedContext(ownerUserId)
        ?.takeIf { cached -> cached.ownerUserId == ownerUserId && cached.localAccessAllowed && cached.syncAllowed &&
            !cached.isLoading && cached.selectedShop?.shopId == selectedShopStore.getSelectedShopId(ownerUserId) &&
            (cached.linkedShops.isEmpty() || cached.selectableShops.any { it.shopId == cached.selectedShop?.shopId }) }

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

    private val cacheJson = Json { ignoreUnknownKeys = false }
    private fun confirmedKey(ownerUserId: String) = "confirmed_local_context:" + task126OwnerHash(ownerUserId)

    override fun getConfirmedContext(ownerUserId: String): ShopContext? {
        val raw = preferences.getString(confirmedKey(ownerUserId), null) ?: return null
        if (raw.length > 65_536) return null
        return runCatching { cacheJson.decodeFromString<ShopContext>(raw) }.getOrNull()
            ?.takeIf { it.ownerUserId == ownerUserId }
    }

    override fun setConfirmedContext(ownerUserId: String, context: ShopContext) {
        if (context.ownerUserId != ownerUserId || !context.localAccessAllowed || !context.syncAllowed || context.isLoading) return
        val raw = cacheJson.encodeToString(context.copy(errorMessage = null))
        if (raw.length > 65_536) { clearConfirmedContext(ownerUserId); return }
        preferences.edit().putString(confirmedKey(ownerUserId), raw).commit()
    }

    override fun clearConfirmedContext(ownerUserId: String) {
        preferences.edit().remove(confirmedKey(ownerUserId)).commit()
    }

    private fun denialKey(owner: String, shop: String?, device: String) =
        "confirmed_device_denial:" + task126OwnerHash(owner) + ":" +
            task126OwnerHash(shop ?: "legacy") + ":" + task126OwnerHash(device)

    override fun isDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String): Boolean =
        preferences.getBoolean(denialKey(ownerUserId, shopId, deviceId), false)

    override fun setDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String, denied: Boolean) {
        val edit = preferences.edit()
        val key = denialKey(ownerUserId, shopId, deviceId)
        if (denied) edit.putBoolean(key, true) else edit.remove(key)
        check(edit.commit()) { "device_authorization_persistence_failed" }
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
