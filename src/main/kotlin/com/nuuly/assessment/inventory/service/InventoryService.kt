package com.nuuly.assessment.inventory.service

import com.nuuly.assessment.inventory.domain.InventoryEntity
import com.nuuly.assessment.inventory.domain.exception.InsufficientInventoryException
import com.nuuly.assessment.inventory.domain.exception.InvalidQuantityException
import com.nuuly.assessment.inventory.domain.exception.SkuNotFoundException
import com.nuuly.assessment.inventory.persistence.InventoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Inventory business operations. Every method runs in a database transaction; the class
 * default is read-only and the two write paths override it.
 *
 * Correctness under concurrency comes entirely from PostgreSQL row locks taken inside these
 * transactions (tech-spec.md §4), never from JVM-local state, so any number of service
 * replicas can run against the same database.
 */
@Service
@Transactional(readOnly = true)
class InventoryService(private val repository: InventoryRepository) {

    fun getInventory(skuId: String): InventoryEntity =
        repository.findByIdOrNull(skuId) ?: throw SkuNotFoundException(skuId)

    fun listAllInventory(): List<InventoryEntity> =
        repository.findAllByOrderBySkuIdAsc()

    /**
     * Creates the SKU with [quantityToAdd] units, or adds them to existing stock, in a single
     * atomic upsert. Concurrent restocks of the same SKU (including racing first-time creates)
     * serialize on the row inside PostgreSQL and are never lost.
     */
    @Transactional
    fun createOrUpdateInventory(skuId: String, quantityToAdd: Int): InventoryEntity {
        requirePositive(quantityToAdd)

        val rowsWritten = repository.upsertAddQuantity(skuId, quantityToAdd)
        if (rowsWritten == 0) {
            throw InvalidQuantityException("Resulting quantity exceeds maximum of ${Int.MAX_VALUE}")
        }

        // The upsert holds this row's lock until commit, so this read returns exactly the
        // state this transaction wrote.
        return repository.findByIdOrNull(skuId)
            ?: throw IllegalStateException("Inventory row for SKU $skuId missing after upsert")
    }

    /**
     * Deducts [quantityToDeduct] units, all or nothing.
     *
     * The row is locked with `SELECT … FOR UPDATE` before its quantity is read, so concurrent
     * purchases of the same SKU, from any replica, are applied one at a time against the latest
     * committed stock. The lock is released when this transaction commits or rolls back.
     */
    @Transactional
    fun purchaseInventory(skuId: String, quantityToDeduct: Int): InventoryEntity {
        requirePositive(quantityToDeduct)

        val item = repository.findByIdForUpdate(skuId) ?: throw SkuNotFoundException(skuId)
        if (item.quantity < quantityToDeduct) {
            throw InsufficientInventoryException(skuId, quantityToDeduct, item.quantity)
        }

        item.quantity -= quantityToDeduct
        // Flush now rather than at commit so a database-side rejection (e.g. the non-negative
        // CHECK constraint) surfaces here as a translated DataAccessException.
        return repository.saveAndFlush(item)
    }

    // The API layer validates quantities first; this guards every other caller of the service.
    private fun requirePositive(quantity: Int) {
        if (quantity < 1) {
            throw InvalidQuantityException("quantity must be greater than or equal to 1")
        }
    }
}
