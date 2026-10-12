package com.nuuly.assessment.inventory.persistence

import com.nuuly.assessment.inventory.domain.InventoryEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Data access for the `inventory` table.
 *
 * Standard CRUD comes from [JpaRepository]. The two write paths that must be safe across
 * every replica of the service rely on PostgreSQL row locks rather than JVM-local locking:
 * [findByIdForUpdate] for purchases and [upsertAddQuantity] for stock additions.
 */
interface InventoryRepository : JpaRepository<InventoryEntity, String> {

    /**
     * Loads the SKU row and takes an exclusive row lock held until the surrounding
     * transaction commits or rolls back. Concurrent purchases of the same SKU queue here,
     * and each one re-reads the latest committed quantity once it acquires the lock.
     *
     * Must be called inside a read-write transaction; returns null when the SKU does not exist.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.skuId = :skuId")
    fun findByIdForUpdate(@Param("skuId") skuId: String): InventoryEntity?

    /**
     * Atomically creates the SKU with [quantity] units, or adds [quantity] to its current stock.
     *
     * The addition is computed from the row's current value inside PostgreSQL, so concurrent
     * restocks are never lost, and a racing first-time insert of the same SKU takes the update
     * branch instead of failing on the primary key. The conflict update locks the row, which
     * serializes it against [findByIdForUpdate].
     *
     * Returns the number of rows written: 1 on success, 0 when the addition would overflow a
     * 32-bit INTEGER (the row is left unchanged).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory (sku_id, quantity)
            VALUES (:skuId, :quantity)
            ON CONFLICT (sku_id) DO UPDATE
                SET quantity = inventory.quantity + EXCLUDED.quantity
                WHERE inventory.quantity <= 2147483647 - EXCLUDED.quantity
        """,
        nativeQuery = true,
    )
    fun upsertAddQuantity(@Param("skuId") skuId: String, @Param("quantity") quantity: Int): Int

    /** All SKUs in byte-wise `sku_id` order, matching the column's "C" collation. */
    fun findAllByOrderBySkuIdAsc(): List<InventoryEntity>
}
