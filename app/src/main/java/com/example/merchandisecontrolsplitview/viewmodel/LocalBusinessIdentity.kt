package com.example.merchandisecontrolsplitview.viewmodel

import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.LocalAuthIdentity
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGate
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGateDecision
import com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope

/** Account proof is necessary but never sufficient to present or edit the local business scope. */
internal fun qualifiedLocalBusinessOwner(
    auth: AuthState,
    identity: LocalAuthIdentity?,
    identityCurrent: Boolean,
    shop: ShopContext?,
    scope: Task126BusinessDataScopeState
): String? {
    val owner = when (auth) {
        is AuthState.SignedIn -> auth.userId.takeIf {
            identity == null || (identityCurrent && identity.userId == it)
        }
        AuthState.Checking -> identity?.takeIf { identityCurrent }?.userId
        else -> null
    }?.takeIf(String::isNotBlank) ?: return null
    if (!scope.allowsLocalOperations) return null
    if (shop != null && (shop.ownerUserId != owner || !shop.localAccessAllowed)) return null
    val admitted = scope.localAccessScope ?: scope.boundScope ?: return null
    if (Task126OwnerStoreGate.validate(admitted, task126ActiveOwnerStoreScope(owner, shop?.selectedShop)) !=
        Task126OwnerStoreGateDecision.Allowed) return null
    return owner
}
