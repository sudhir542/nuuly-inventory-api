package com.nuuly.assessment.inventory.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request body for adding stock and for purchases (OpenAPI schema `InventoryQuantity`).
 *
 * [quantity] is nullable on purpose: a missing field and an explicit `null` both reach Bean
 * Validation and fail `@NotNull` with the same message, instead of failing earlier inside
 * Jackson with a different exception. After validation it is guaranteed non-null.
 */
data class InventoryQuantity(
    @field:NotNull(message = "quantity is required")
    @field:Min(value = 1, message = "quantity must be greater than or equal to 1")
    val quantity: Int?,
)
