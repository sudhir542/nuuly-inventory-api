package com.nuuly.assessment.inventory.api

import com.nuuly.assessment.inventory.api.dto.InventoryItem
import com.nuuly.assessment.inventory.api.dto.InventoryQuantity
import com.nuuly.assessment.inventory.domain.InventoryEntity
import com.nuuly.assessment.inventory.service.InventoryService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP mapping for the Inventory API. Holds no business logic; errors are rendered as
 * `text/plain` by `GlobalExceptionHandler`.
 *
 * Deliberately not annotated with `@Validated`: the constraint annotations on the parameters
 * activate Spring's built-in method validation, which reports path and body violations
 * together as a single `HandlerMethodValidationException`.
 */
@RestController
@RequestMapping("/inventory")
class InventoryController(private val inventoryService: InventoryService) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listInventory(): List<InventoryItem> =
        inventoryService.listAllInventory().map(InventoryItem::from)

    // No skuId constraints here: the contract allows only 200 or 404 for this operation, and a
    // blank or over-long SKU cannot exist in the table, so the lookup returns 404 naturally.
    @GetMapping("/{skuId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getInventory(@PathVariable skuId: String): InventoryItem =
        InventoryItem.from(inventoryService.getInventory(skuId))

    @PostMapping(
        "/{skuId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createInventory(
        @PathVariable
        @NotBlank(message = SKU_ID_MESSAGE)
        @Size(max = InventoryEntity.MAX_SKU_ID_LENGTH, message = SKU_ID_MESSAGE)
        skuId: String,
        @Valid @RequestBody request: InventoryQuantity,
    ): InventoryItem =
        // quantity is non-null here: @NotNull has already been enforced.
        InventoryItem.from(inventoryService.createOrUpdateInventory(skuId, request.quantity!!))

    @PostMapping(
        "/{skuId}/purchase",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun purchaseItem(
        @PathVariable
        @NotBlank(message = SKU_ID_MESSAGE)
        @Size(max = InventoryEntity.MAX_SKU_ID_LENGTH, message = SKU_ID_MESSAGE)
        skuId: String,
        @Valid @RequestBody request: InventoryQuantity,
    ): InventoryItem =
        // quantity is non-null here: @NotNull has already been enforced.
        InventoryItem.from(inventoryService.purchaseInventory(skuId, request.quantity!!))

    private companion object {
        const val SKU_ID_MESSAGE = "skuId must be between 1 and 64 characters"
    }
}
