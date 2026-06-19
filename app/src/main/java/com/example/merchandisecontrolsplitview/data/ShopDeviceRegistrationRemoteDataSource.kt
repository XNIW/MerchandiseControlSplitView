package com.example.merchandisecontrolsplitview.data

import android.os.Build
import android.util.Log
import com.example.merchandisecontrolsplitview.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val TAG = "ShopDeviceRegistry"
private const val DEVICE_STATUS_CACHE_TTL_MS = 15_000L

class DeviceInstallIdProvider(
    private val dao: SyncEventDeviceStateDao
) {
    suspend fun getOrCreate(): String {
        dao.get()?.let { return it.deviceId }
        val generated = java.util.UUID.randomUUID().toString()
        dao.insert(
            SyncEventDeviceState(
                deviceId = generated,
                createdAtMs = System.currentTimeMillis()
            )
        )
        return dao.get()?.deviceId ?: generated
    }
}

interface ShopDeviceRegistrationRemote {
    val isConfigured: Boolean

    suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult>

    suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot>
}

class ShopDeviceRegistrationRemoteDataSource(
    private val client: SupabaseClient?,
    private val installIdProvider: DeviceInstallIdProvider
) : ShopDeviceRegistrationRemote {
    override val isConfigured: Boolean get() = client != null

    override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
        runCatching {
            val resolvedClient = client ?: error("Supabase non configurato")
            val installId = installIdProvider.getOrCreate()
            val params = ShopDeviceRegistrationRpcParams(
                appVersion = BuildConfig.VERSION_NAME,
                deviceIdentifier = installId,
                deviceType = "mobile",
                displayName = androidDisplayName(),
                metadata = redactedDeviceMetadata(reason)
            )

            resolvedClient
                .postgrest
                .rpc("shop_device_register_current_owner", params)
                .decodeAs<ShopDeviceRegistrationResult>()
        }.onFailure { error ->
            Log.i(
                TAG,
                "device register skipped reason=$reason errClass=${error::class.java.simpleName}"
            )
        }

    override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
        runCatching {
            val resolvedClient = client ?: error("Supabase non configurato")
            val installId = installIdProvider.getOrCreate()
            val params = ShopDeviceStatusRpcParams(deviceIdentifier = installId)

            resolvedClient
                .postgrest
                .rpc("shop_device_status_current_owner", params)
                .decodeAs<ShopDeviceStatusRpcResult>()
                .toSnapshot()
        }.onFailure { error ->
            Log.i(
                TAG,
                "device status skipped reason=$reason errClass=${error::class.java.simpleName}"
            )
        }
}

@Serializable
data class ShopDeviceRegistrationRpcParams(
    @SerialName("p_device_identifier") val deviceIdentifier: String,
    @SerialName("p_device_type") val deviceType: String = "mobile",
    @SerialName("p_display_name") val displayName: String,
    @SerialName("p_app_version") val appVersion: String,
    @SerialName("p_metadata") val metadata: JsonObject
)

@Serializable
data class ShopDeviceRegistrationResult(
    val ok: Boolean = false,
    val code: String = "unknown",
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("target_id") val targetId: String? = null
)

@Serializable
data class ShopDeviceStatusRpcParams(
    @SerialName("p_device_identifier") val deviceIdentifier: String
)

@Serializable
data class ShopDeviceStatusRpcResult(
    val ok: Boolean = false,
    val code: String = "unknown",
    val status: String = "unknown",
    @SerialName("can_write") val canWrite: Boolean = false,
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("recommended_action") val recommendedAction: String? = null
) {
    fun toSnapshot(): ShopDeviceAuthorizationSnapshot =
        ShopDeviceAuthorizationSnapshot(
            status = status.ifBlank { code },
            code = code.ifBlank { "unknown" },
            canWrite = canWrite && status == "active",
            serverTime = serverTime,
            lastSeenAt = lastSeenAt,
            reasonCode = reasonCode ?: code,
            recommendedAction = recommendedAction ?: "contact_shop_admin",
            checkedAtMs = System.currentTimeMillis()
        )
}

data class ShopDeviceAuthorizationSnapshot(
    val status: String,
    val code: String,
    val canWrite: Boolean,
    val serverTime: String?,
    val lastSeenAt: String?,
    val reasonCode: String,
    val recommendedAction: String,
    val checkedAtMs: Long
)

class ShopDeviceAuthorizationBlockedException(
    val snapshot: ShopDeviceAuthorizationSnapshot
) : IllegalStateException(
    "Cloud sync blocked by device status=${snapshot.status} code=${snapshot.code}"
)

class ShopDeviceAuthorizationRepository(
    private val remote: ShopDeviceRegistrationRemote,
    private val cacheTtlMs: Long = DEVICE_STATUS_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var lastSnapshot: ShopDeviceAuthorizationSnapshot? = null
    private val statusCheckMutex = Mutex()

    suspend fun registerHeartbeatAndCheck(reason: String): Result<ShopDeviceAuthorizationSnapshot> {
        remote.registerCurrentOwnerDevice(reason)
        return checkStatus(reason = reason, force = true)
    }

    suspend fun checkStatus(
        reason: String,
        force: Boolean = false
    ): Result<ShopDeviceAuthorizationSnapshot> {
        val now = clockMs()
        val cached = lastSnapshot
        if (!force && cached != null && now - cached.checkedAtMs < cacheTtlMs) {
            return Result.success(cached)
        }

        return statusCheckMutex.withLock {
            val lockedNow = clockMs()
            val lockedCached = lastSnapshot
            if (!force && lockedCached != null && lockedNow - lockedCached.checkedAtMs < cacheTtlMs) {
                return@withLock Result.success(lockedCached)
            }

            remote.currentOwnerDeviceStatus(reason)
                .onSuccess { snapshot ->
                    lastSnapshot = snapshot.copy(checkedAtMs = lockedNow)
                }
                .recoverCatching { error ->
                    cachedActiveSnapshotForTransientCancellation(reason, error, lockedNow)
                        ?: return@recoverCatching networkErrorSnapshot(error, lockedNow)
                }
        }
    }

    suspend fun ensureActiveForCloudWrite(reason: String): Result<ShopDeviceAuthorizationSnapshot> {
        val snapshot = checkStatus(reason = reason, force = true)
            .getOrElse { error ->
                networkErrorSnapshot(error, clockMs())
            }

        return if (snapshot.status == "active" && snapshot.canWrite) {
            Result.success(snapshot)
        } else {
            Result.failure(ShopDeviceAuthorizationBlockedException(snapshot))
        }
    }

    private fun cachedActiveSnapshotForTransientCancellation(
        reason: String,
        error: Throwable,
        checkedAtMs: Long
    ): ShopDeviceAuthorizationSnapshot? {
        if (error !is CancellationException) return null
        if (reason.startsWith("manual_", ignoreCase = true)) return null

        val cached = lastSnapshot ?: return null
        if (checkedAtMs - cached.checkedAtMs >= cacheTtlMs) return null
        if (cached.status != "active" || !cached.canWrite) return null

        return cached.copy(checkedAtMs = checkedAtMs)
    }

    private fun networkErrorSnapshot(error: Throwable, checkedAtMs: Long): ShopDeviceAuthorizationSnapshot =
        ShopDeviceAuthorizationSnapshot(
            status = "network_error",
            code = error::class.java.simpleName.ifBlank { "network_error" },
            canWrite = false,
            serverTime = null,
            lastSeenAt = lastSnapshot?.lastSeenAt,
            reasonCode = "network_error",
            recommendedAction = "retry_when_online",
            checkedAtMs = checkedAtMs
        )
}

private fun androidDisplayName(): String =
    listOf("Android", Build.MANUFACTURER, Build.MODEL)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")
        .take(120)

private fun redactedDeviceMetadata(reason: String): JsonObject =
    buildJsonObject {
        put("platform", JsonPrimitive("android"))
        put("model", JsonPrimitive(Build.MODEL.take(80)))
        put("os_version", JsonPrimitive(Build.VERSION.RELEASE.take(40)))
        put("app_version_present", JsonPrimitive(BuildConfig.VERSION_NAME.isNotBlank()))
        put("simulator", JsonPrimitive(isLikelyAndroidEmulator()))
        put("reason", JsonPrimitive(reason.take(40)))
    }

private fun isLikelyAndroidEmulator(): Boolean {
    val product = Build.PRODUCT.lowercase()
    val model = Build.MODEL.lowercase()
    val manufacturer = Build.MANUFACTURER.lowercase()

    return product.contains("sdk") ||
        model.contains("emulator") ||
        model.contains("sdk") ||
        manufacturer.contains("genymotion")
}
