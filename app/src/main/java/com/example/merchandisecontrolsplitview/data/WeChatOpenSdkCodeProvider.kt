package com.example.merchandisecontrolsplitview.data

import android.content.Context
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class WeChatOpenSdkCallback(
    val code: String?,
    val errorCode: Int,
    val state: String?
)

internal class WeChatCallbackBroker(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 5 * 60 * 1_000L
) {
    private data class Pending(
        val createdAtEpochMillis: Long,
        val deferred: CompletableDeferred<WeChatCodeResult>,
        val state: String
    )

    private var pending: Pending? = null

    suspend fun request(
        state: String,
        sender: () -> Boolean
    ): WeChatCodeResult {
        val deferred = CompletableDeferred<WeChatCodeResult>()
        synchronized(this) {
            if (pending != null) return WeChatCodeResult.Failure(WeChatAuthError.CALLBACK_DUPLICATE)
            pending = Pending(nowEpochMillis(), deferred, state)
        }
        val sent = runCatching(sender).getOrDefault(false)
        if (!sent) {
            clear(deferred)
            return WeChatCodeResult.Failure(WeChatAuthError.BACKEND_ERROR)
        }

        val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        clear(deferred)
        return result ?: WeChatCodeResult.Failure(WeChatAuthError.CALLBACK_EXPIRED)
    }

    fun deliver(callback: WeChatOpenSdkCallback): Boolean {
        val current = synchronized(this) {
            val value = pending ?: return false
            pending = null
            value
        }
        val result = when {
            nowEpochMillis() - current.createdAtEpochMillis !in 0..timeoutMillis ->
                WeChatCodeResult.Failure(WeChatAuthError.CALLBACK_EXPIRED)
            callback.state == null || !constantTimeEquals(current.state, callback.state) ->
                WeChatCodeResult.Failure(WeChatAuthError.STATE_MISMATCH)
            callback.errorCode == BaseResp.ErrCode.ERR_USER_CANCEL -> WeChatCodeResult.Cancelled
            callback.errorCode == BaseResp.ErrCode.ERR_AUTH_DENIED -> WeChatCodeResult.Denied
            callback.errorCode != BaseResp.ErrCode.ERR_OK ->
                WeChatCodeResult.Failure(WeChatAuthError.BACKEND_ERROR)
            callback.code.isNullOrBlank() -> WeChatCodeResult.Failure(WeChatAuthError.CODE_MISSING)
            else -> WeChatCodeResult.Success(callback.code, callback.state)
        }
        current.deferred.complete(result)
        return true
    }

    private fun clear(deferred: CompletableDeferred<WeChatCodeResult>) {
        synchronized(this) {
            if (pending?.deferred === deferred) pending = null
        }
    }
}

object WeChatCallbackRegistry {
    private val broker = WeChatCallbackBroker()

    internal suspend fun request(state: String, sender: () -> Boolean): WeChatCodeResult =
        broker.request(state, sender)

    fun deliver(code: String?, state: String?, errorCode: Int): Boolean =
        broker.deliver(WeChatOpenSdkCallback(code = code, errorCode = errorCode, state = state))
}

class WeChatOpenSdkCodeProvider(
    applicationContext: Context,
    private val appId: String,
    private val api: IWXAPI = WXAPIFactory.createWXAPI(applicationContext, appId, true)
) : WeChatCodeProvider {
    override val isConfigured: Boolean = APP_ID_PATTERN.matches(appId)

    init {
        if (isConfigured) api.registerApp(appId)
    }

    override fun isWeChatInstalled(context: Context): Boolean =
        isConfigured && api.isWXAppInstalled

    override suspend fun requestCode(context: Context, state: String): WeChatCodeResult {
        if (!isWeChatInstalled(context)) return WeChatCodeResult.NotInstalled
        return WeChatCallbackRegistry.request(state) {
            api.sendReq(
                SendAuth.Req().apply {
                    scope = "snsapi_userinfo"
                    this.state = state
                }
            )
        }
    }

    companion object {
        private val APP_ID_PATTERN = Regex("^wx[0-9a-fA-F]{16}$")
        const val SDK_VERSION = "6.8.34"
        const val WECHAT_PACKAGE = ConstantsAPI.WXApp.WXAPP_PACKAGE_NAME
    }
}
