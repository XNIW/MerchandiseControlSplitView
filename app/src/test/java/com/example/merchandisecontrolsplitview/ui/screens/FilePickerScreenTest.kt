package com.example.merchandisecontrolsplitview.ui.screens

import com.example.merchandisecontrolsplitview.data.LinkedShop
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.ShopContextResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerScreenTest {
    @Test
    fun zeroShopsKeepsInventoryHeaderClean() {
        val presentation = inventoryShopHeaderPresentation(ShopContext.legacy(ownerUserId = OWNER_A))

        assertNull(presentation)
    }

    @Test
    fun oneShopShowsNameWithoutSwitcher() {
        val context = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(shop("shop-a", "Moda Lina")),
            persistedShopId = null
        ).context

        val presentation = inventoryShopHeaderPresentation(context)

        assertEquals("Moda Lina", presentation?.shopName)
        assertFalse(presentation?.showsSwitcher ?: true)
    }

    @Test
    fun multipleShopsShowsNameWithSwitcher() {
        val context = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(shop("shop-a", "Shop A"), shop("shop-b", "Shop B")),
            persistedShopId = "shop-b"
        ).context

        val presentation = inventoryShopHeaderPresentation(context)

        assertEquals("Shop B", presentation?.shopName)
        assertTrue(presentation?.showsSwitcher ?: false)
    }

    @Test
    fun accountWithoutLinkedShopsDoesNotLeakPreviousHeader() {
        val context = ShopContextResolver.resolve(
            ownerUserId = OWNER_B,
            linkedShops = emptyList(),
            persistedShopId = "shop-a"
        ).context

        val presentation = inventoryShopHeaderPresentation(context)

        assertNull(presentation)
    }

    @Test
    fun revokedShopFallsBackToRemainingValidHeader() {
        val context = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(
                shop("shop-a", "Revoked", status = "revoked", selectable = false),
                shop("shop-b", "Valid")
            ),
            persistedShopId = "shop-a"
        ).context

        val presentation = inventoryShopHeaderPresentation(context)

        assertEquals("Valid", presentation?.shopName)
        assertFalse(presentation?.showsSwitcher ?: true)
    }

    @Test
    fun revokedOnlyShopReturnsCleanHeader() {
        val context = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(shop("shop-a", "Revoked", status = "suspended", selectable = false)),
            persistedShopId = "shop-a"
        ).context

        val presentation = inventoryShopHeaderPresentation(context)

        assertNull(presentation)
    }

    private fun shop(
        id: String,
        name: String,
        status: String = "active",
        selectable: Boolean = true
    ) = LinkedShop(
        shopId = id,
        code = id.uppercase(),
        name = name,
        role = "shop_owner",
        status = status,
        selectable = selectable,
        canWrite = true
    )

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-0000000000aa"
        const val OWNER_B = "00000000-0000-4000-8000-0000000000bb"
    }
}
