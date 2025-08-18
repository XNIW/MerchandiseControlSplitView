package com.example.merchandisecontrolsplitview.data

import androidx.room.DatabaseView

@DatabaseView(
    viewName = "product_price_summary",
    value = """
    SELECT 
      p.id AS productId,
      -- ultimo prezzo di acquisto
      (SELECT price FROM product_prices pr 
         WHERE pr.productId = p.id AND pr.type = 'PURCHASE'
         ORDER BY pr.effectiveAt DESC LIMIT 1) AS lastPurchase,
      -- penultimo prezzo di acquisto
      (SELECT price FROM product_prices pr 
         WHERE pr.productId = p.id AND pr.type = 'PURCHASE'
           AND pr.effectiveAt < (SELECT MAX(effectiveAt) FROM product_prices pr3 
                                 WHERE pr3.productId = p.id AND pr3.type = 'PURCHASE')
         ORDER BY pr.effectiveAt DESC LIMIT 1) AS prevPurchase,
      -- ultimo prezzo di vendita
      (SELECT price FROM product_prices pr 
         WHERE pr.productId = p.id AND pr.type = 'RETAIL'
         ORDER BY pr.effectiveAt DESC LIMIT 1) AS lastRetail,
      -- penultimo prezzo di vendita
      (SELECT price FROM product_prices pr 
         WHERE pr.productId = p.id AND pr.type = 'RETAIL'
           AND pr.effectiveAt < (SELECT MAX(effectiveAt) FROM product_prices pr3 
                                 WHERE pr3.productId = p.id AND pr3.type = 'RETAIL')
         ORDER BY pr.effectiveAt DESC LIMIT 1) AS prevRetail
    FROM products p
    """
)
data class ProductPriceSummary(
    val productId: Long,
    val lastPurchase: Double?,
    val prevPurchase: Double?,
    val lastRetail: Double?,
    val prevRetail: Double?
)
