package com.example.merchandisecontrolsplitview.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.merchandisecontrolsplitview.BuildConfig
import com.example.merchandisecontrolsplitview.data.WeChatCallbackRegistry
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

class WXEntryActivity : Activity(), IWXAPIEventHandler {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onReq(request: BaseReq?) {
        finish()
    }

    override fun onResp(response: BaseResp?) {
        if (response is SendAuth.Resp) {
            WeChatCallbackRegistry.deliver(
                code = response.code,
                state = response.state,
                errorCode = response.errCode
            )
        }
        finish()
    }

    private fun route(intent: Intent?) {
        val appId = BuildConfig.WECHAT_ANDROID_APP_ID
        if (!BuildConfig.WECHAT_AUTH_ANDROID_ENABLED || appId.isBlank() || intent == null) {
            finish()
            return
        }
        val handled = runCatching {
            WXAPIFactory.createWXAPI(this, appId, false).handleIntent(intent, this)
        }.getOrDefault(false)
        if (!handled) finish()
    }
}
