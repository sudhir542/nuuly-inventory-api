package com.nuuly.assessment.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Entry point for the Inventory API. Placed in the root package so component scanning,
 * JPA entity scanning, and Spring Data repository detection cover every sub-package
 * (`api`, `domain`, `persistence`) without explicit configuration.
 */
@SpringBootApplication
class InventoryApplication

fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}
