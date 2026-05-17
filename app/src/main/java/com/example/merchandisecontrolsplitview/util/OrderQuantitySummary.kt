package com.example.merchandisecontrolsplitview.util

fun calculateTotalQuantityFromRows(data: List<List<String>>): Double? {
    val header = data.firstOrNull() ?: return null
    val quantityIndex = header.indexOf("quantity")
    if (quantityIndex < 0) return null

    var parsedQuantityCount = 0
    var totalQuantity = 0.0

    data.drop(1).forEach { row ->
        val quantity = parseUserQuantityInput(row.getOrNull(quantityIndex)) ?: return@forEach
        parsedQuantityCount += 1
        if (quantity > 0.0) {
            totalQuantity += quantity
        }
    }

    return totalQuantity.takeIf { parsedQuantityCount > 0 }
}
