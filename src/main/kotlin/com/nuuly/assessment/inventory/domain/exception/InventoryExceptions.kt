package com.nuuly.assessment.inventory.domain.exception

/**
 * Base type for business-rule failures raised by the inventory domain.
 *
 * These are expected outcomes (an unknown SKU, a sold-out item), not programming errors, so
 * they skip stack-trace capture: under a flash sale the insufficient-inventory path can fire
 * thousands of times per second and the trace carries no diagnostic value. The `message` is
 * the exact text returned to API clients as the `text/plain` error body.
 *
 * Being unchecked, every subtype rolls back the surrounding `@Transactional` method and
 * releases any row lock it holds.
 */
sealed class InventoryException(message: String) :
    RuntimeException(message, null, false, false)

/** The requested SKU has no row in inventory. Mapped to `404 Not Found`. */
class SkuNotFoundException(val skuId: String) :
    InventoryException("SKU not found: $skuId")

/**
 * A purchase asked for more units than are on hand; nothing was deducted.
 * Mapped to `400 Bad Request`. [requested] and [available] are kept for logging and metrics
 * only; the client-facing message deliberately does not disclose stock levels.
 */
class InsufficientInventoryException(
    val skuId: String,
    val requested: Int,
    val available: Int,
) : InventoryException("Insufficient inventory")

/**
 * A stock operation would move a quantity outside its allowed range, for example a restock
 * that would push stock past the 32-bit INTEGER maximum. Mapped to `400 Bad Request`.
 */
class InvalidQuantityException(message: String) :
    InventoryException(message)
