package com.nuuly.assessment.inventory.api.dto

import com.nuuly.assessment.inventory.domain.InventoryEntity

/** Response body describing a SKU's stock on hand (OpenAPI schema `InventoryItem`). */
data class InventoryItem(
    val skuId: String,
    val quantity: Int,
) {
    companion object {
        fun from(entity: InventoryEntity) = InventoryItem(skuId = entity.skuId, quantity = entity.quantity)
    }
}
