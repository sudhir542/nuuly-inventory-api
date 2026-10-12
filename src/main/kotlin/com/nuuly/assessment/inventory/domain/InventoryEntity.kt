package com.nuuly.assessment.inventory.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Quantity on hand for a single SKU, mapped to the `inventory` table created by
 * `V1__create_inventory_table.sql`.
 *
 * The Bean Validation constraints mirror the table's CHECK constraints and column sizes, so
 * Hibernate rejects an invalid row before it reaches the database. The database constraints
 * remain the final authority.
 *
 * `createdAt` and `updatedAt` are owned by PostgreSQL (column defaults plus the
 * `trg_inventory_set_updated_at` trigger). They are mapped read-only so Hibernate never writes
 * application-clock values over them, and they reflect the database state as of the last load.
 */
@Entity
@Table(name = "inventory")
class InventoryEntity(
    @Id
    @Column(name = "sku_id", length = MAX_SKU_ID_LENGTH, nullable = false, updatable = false)
    @field:NotBlank
    @field:Size(max = MAX_SKU_ID_LENGTH)
    val skuId: String,

    @Column(name = "quantity", nullable = false)
    @field:Min(0)
    var quantity: Int,
) {

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: Instant? = null
        protected set

    // Identity is the assigned natural key, which is fixed from construction, so it is safe
    // to use in equals/hashCode across new, managed, and detached states and through proxies.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InventoryEntity) return false
        return skuId == other.skuId
    }

    override fun hashCode(): Int = skuId.hashCode()

    override fun toString(): String = "InventoryEntity(skuId=$skuId, quantity=$quantity)"

    companion object {
        const val MAX_SKU_ID_LENGTH = 64
    }
}
