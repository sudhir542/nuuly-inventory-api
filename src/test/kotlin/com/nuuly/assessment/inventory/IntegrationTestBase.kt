package com.nuuly.assessment.inventory

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class for integration tests that need a real PostgreSQL.
 *
 * One container is started per test JVM and shared by every subclass (the singleton-container
 * pattern). A per-class `@Container` would stop the database between classes while Spring's
 * cached application context still pointed at it. Testcontainers' Ryuk sidecar removes the
 * container when the JVM exits.
 *
 * The image matches docker-compose.yml so tests exercise the same PostgreSQL major version as
 * local development. Flyway applies `V1__create_inventory_table.sql` on context startup, and
 * `ddl-auto: validate` then checks the entity mapping against it.
 */
abstract class IntegrationTestBase {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    /** Every test starts from an empty table, independent of execution order. */
    @BeforeEach
    fun resetInventory() {
        jdbcTemplate.execute("TRUNCATE TABLE inventory")
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("inventory_db")
                .withUsername("inventory_test")
                .withPassword("inventory_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
