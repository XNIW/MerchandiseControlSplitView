package com.example.merchandisecontrolsplitview.data

data class ProductUpdate(
    val oldProduct: Product,
    val newProduct: Product,
    val changedFields: List<String> // Esempio: ["productName", "purchasePrice"]
)