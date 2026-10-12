package com.nuuly.assessment.inventory.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nuuly.assessment.inventory.IntegrationTestBase
import com.nuuly.assessment.inventory.api.dto.InventoryItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests of the Inventory API over real HTTP against a real PostgreSQL, covering the
 * OpenAPI contract, the text/plain error mapping (tech-spec.md §5.4), and the concurrency
 * guarantees of the pessimistic-locking design (tech-spec.md §4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryApiIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    // --- GET /inventory ----------------------------------------------------------------------

    @Test
    fun `list returns an empty JSON array when no SKUs exist`() {
        val response = rest.getForEntity("/inventory", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJson(response)
        assertThat(readItems(response)).isEmpty()
    }

    @Test
    fun `list returns every SKU in byte-wise sku order`() {
        addStock("widget", 3)
        addStock("Widget", 1)
        addStock("CW-XYCS-BM-01", 10)

        val response = rest.getForEntity("/inventory", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(readItems(response)).containsExactly(
            InventoryItem("CW-XYCS-BM-01", 10),
            InventoryItem("Widget", 1),
            InventoryItem("widget", 3),
        )
    }

    // --- POST /inventory/{skuId} --------------------------------------------------------------

    @Test
    fun `create adds a new SKU with the given quantity`() {
        val response = postJson("/inventory/CW-XYCS-BM-01", """{"quantity":10}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJson(response)
        assertThat(readItem(response)).isEqualTo(InventoryItem("CW-XYCS-BM-01", 10))
        assertThat(quantityInDatabase("CW-XYCS-BM-01")).isEqualTo(10)
    }

    @Test
    fun `create adds to the stock of an existing SKU`() {
        addStock("widget", 10)

        val response = postJson("/inventory/widget", """{"quantity":5}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(readItem(response)).isEqualTo(InventoryItem("widget", 15))
        assertThat(quantityInDatabase("widget")).isEqualTo(15)
    }

    @Test
    fun `create rejects an addition that would overflow and leaves stock unchanged`() {
        addStock("widget", Int.MAX_VALUE)

        val response = postJson("/inventory/widget", """{"quantity":1}""")

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, "Resulting quantity exceeds maximum of 2147483647")
        assertThat(quantityInDatabase("widget")).isEqualTo(Int.MAX_VALUE)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidQuantityBodies")
    fun `create rejects an invalid quantity body with a plain-text 400`(body: String, expectedMessage: String) {
        val response = postJson("/inventory/widget", body)

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, expectedMessage)
        assertThat(skuExists("widget")).isFalse()
    }

    @Test
    fun `create rejects a request that is not JSON with 415`() {
        val headers = HttpHeaders().apply { contentType = MediaType.TEXT_PLAIN }

        val response = rest.exchange("/inventory/widget", HttpMethod.POST, HttpEntity("5", headers), String::class.java)

        assertPlainTextError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json")
    }

    @Test
    fun `create rejects a SKU id longer than 64 characters`() {
        val response = postJson("/inventory/${"x".repeat(65)}", """{"quantity":1}""")

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, "skuId must be between 1 and 64 characters")
    }

    @Test
    fun `create accepts a SKU id of exactly 64 characters`() {
        val skuId = "x".repeat(64)

        val response = postJson("/inventory/$skuId", """{"quantity":1}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(readItem(response)).isEqualTo(InventoryItem(skuId, 1))
    }

    // --- GET /inventory/{skuId} ---------------------------------------------------------------

    @Test
    fun `get returns the current quantity of an existing SKU`() {
        addStock("widget", 7)

        val response = rest.getForEntity("/inventory/widget", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJson(response)
        assertThat(readItem(response)).isEqualTo(InventoryItem("widget", 7))
    }

    @Test
    fun `get returns a plain-text 404 for an unknown SKU`() {
        val response = rest.getForEntity("/inventory/does-not-exist", String::class.java)

        assertPlainTextError(response, HttpStatus.NOT_FOUND, "SKU not found: does-not-exist")
    }

    @Test
    fun `get returns 404 rather than 400 for a SKU id too long to exist`() {
        val skuId = "x".repeat(65)

        val response = rest.getForEntity("/inventory/$skuId", String::class.java)

        assertPlainTextError(response, HttpStatus.NOT_FOUND, "SKU not found: $skuId")
    }

    @Test
    fun `errors stay plain text even when the client accepts only JSON`() {
        val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }

        val response = rest.exchange("/inventory/does-not-exist", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertPlainTextError(response, HttpStatus.NOT_FOUND, "SKU not found: does-not-exist")
    }

    @Test
    fun `SKU ids are case-sensitive`() {
        addStock("widget", 5)

        val response = rest.getForEntity("/inventory/WIDGET", String::class.java)

        assertPlainTextError(response, HttpStatus.NOT_FOUND, "SKU not found: WIDGET")
    }

    // --- POST /inventory/{skuId}/purchase -----------------------------------------------------

    @Test
    fun `purchase deducts stock and returns the remaining quantity`() {
        addStock("widget", 10)

        val response = postJson("/inventory/widget/purchase", """{"quantity":4}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJson(response)
        assertThat(readItem(response)).isEqualTo(InventoryItem("widget", 6))
        assertThat(quantityInDatabase("widget")).isEqualTo(6)
    }

    @Test
    fun `purchase of the entire remaining stock leaves zero`() {
        addStock("widget", 3)

        val response = postJson("/inventory/widget/purchase", """{"quantity":3}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(readItem(response)).isEqualTo(InventoryItem("widget", 0))
        assertThat(quantityInDatabase("widget")).isZero()
    }

    @Test
    fun `purchase of more than is in stock is rejected and deducts nothing`() {
        addStock("widget", 3)

        val response = postJson("/inventory/widget/purchase", """{"quantity":4}""")

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, "Insufficient inventory")
        assertThat(quantityInDatabase("widget")).isEqualTo(3)
    }

    @Test
    fun `purchase from a sold-out SKU is rejected`() {
        addStock("widget", 1)
        postJson("/inventory/widget/purchase", """{"quantity":1}""")

        val response = postJson("/inventory/widget/purchase", """{"quantity":1}""")

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, "Insufficient inventory")
        assertThat(quantityInDatabase("widget")).isZero()
    }

    @Test
    fun `purchase of an unknown SKU returns a plain-text 404`() {
        val response = postJson("/inventory/does-not-exist/purchase", """{"quantity":1}""")

        assertPlainTextError(response, HttpStatus.NOT_FOUND, "SKU not found: does-not-exist")
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidQuantityBodies")
    fun `purchase rejects an invalid quantity body and deducts nothing`(body: String, expectedMessage: String) {
        addStock("widget", 10)

        val response = postJson("/inventory/widget/purchase", body)

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, expectedMessage)
        assertThat(quantityInDatabase("widget")).isEqualTo(10)
    }

    @Test
    fun `purchase validates the body before looking up the SKU`() {
        val response = postJson("/inventory/does-not-exist/purchase", """{"quantity":0}""")

        assertPlainTextError(response, HttpStatus.BAD_REQUEST, "quantity must be greater than or equal to 1")
    }

    // --- Concurrency (tech-spec.md §4) --------------------------------------------------------

    @Test
    fun `concurrent purchases never oversell a constrained SKU`() {
        val initialStock = 100
        val attempts = 250
        addStock("hot-sku", initialStock)

        val responses = runConcurrently(attempts) {
            postJson("/inventory/hot-sku/purchase", """{"quantity":1}""")
        }

        val succeeded = responses.filter { it.statusCode == HttpStatus.OK }
        val rejected = responses.filter { it.statusCode == HttpStatus.BAD_REQUEST }

        // Every request either bought exactly one unit or was cleanly rejected; nothing else.
        assertThat(succeeded).hasSize(initialStock)
        assertThat(rejected).hasSize(attempts - initialStock)
        assertThat(rejected).allSatisfy { assertThat(it.body).isEqualTo("Insufficient inventory") }

        // Each successful purchase observed a distinct stock level, so no two purchases read the
        // same quantity: the remaining values are exactly 99, 98, ..., 0.
        val remainingAfterEachPurchase = succeeded.map { readItem(it).quantity }
        assertThat(remainingAfterEachPurchase).containsExactlyInAnyOrderElementsOf((0 until initialStock).toList())

        assertThat(quantityInDatabase("hot-sku")).isZero()
    }

    @Test
    fun `concurrent first-time restocks of a new SKU are all applied`() {
        val requests = 50
        val unitsPerRequest = 2

        val responses = runConcurrently(requests) {
            postJson("/inventory/new-sku", """{"quantity":$unitsPerRequest}""")
        }

        assertThat(responses).allSatisfy { assertThat(it.statusCode).isEqualTo(HttpStatus.OK) }
        assertThat(quantityInDatabase("new-sku")).isEqualTo(requests * unitsPerRequest)
    }

    @Test
    fun `concurrent restocks and purchases preserve the stock invariant`() {
        val initialStock = 20
        val restockUnits = 3
        val purchaseUnits = 5
        addStock("mixed-sku", initialStock)
        val unitsAdded = AtomicInteger()
        val unitsSold = AtomicInteger()

        val responses = runConcurrently(200) {
            if (ThreadLocalRandom.current().nextBoolean()) {
                postJson("/inventory/mixed-sku", """{"quantity":$restockUnits}""").also {
                    if (it.statusCode == HttpStatus.OK) unitsAdded.addAndGet(restockUnits)
                }
            } else {
                postJson("/inventory/mixed-sku/purchase", """{"quantity":$purchaseUnits}""").also {
                    if (it.statusCode == HttpStatus.OK) unitsSold.addAndGet(purchaseUnits)
                }
            }
        }

        assertThat(responses).allSatisfy {
            assertThat(it.statusCode).isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST)
            if (it.statusCode == HttpStatus.BAD_REQUEST) assertThat(it.body).isEqualTo("Insufficient inventory")
        }
        val finalStock = quantityInDatabase("mixed-sku")
        assertThat(finalStock).isEqualTo(initialStock + unitsAdded.get() - unitsSold.get())
        assertThat(finalStock).isGreaterThanOrEqualTo(0)
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun postJson(path: String, body: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    private fun addStock(skuId: String, quantity: Int) {
        val response = postJson("/inventory/$skuId", """{"quantity":$quantity}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    /**
     * Runs [request] [times] times from a fixed pool of threads. All tasks wait on one latch so
     * they hit the server together rather than trickling in as threads start.
     */
    private fun runConcurrently(times: Int, request: () -> ResponseEntity<String>): List<ResponseEntity<String>> {
        val executor = Executors.newFixedThreadPool(CLIENT_THREADS)
        val startGate = CountDownLatch(1)
        val responses = Collections.synchronizedList(ArrayList<ResponseEntity<String>>(times))
        try {
            val futures = (1..times).map {
                executor.submit {
                    startGate.await()
                    responses += request()
                }
            }
            startGate.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        return responses.toList()
    }

    private fun readItem(response: ResponseEntity<String>): InventoryItem =
        objectMapper.readValue(response.body!!)

    private fun readItems(response: ResponseEntity<String>): List<InventoryItem> =
        objectMapper.readValue(response.body!!)

    private fun assertJson(response: ResponseEntity<String>) {
        assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue()
    }

    private fun assertPlainTextError(response: ResponseEntity<String>, status: HttpStatus, message: String) {
        assertThat(response.statusCode).isEqualTo(status)
        assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue()
        assertThat(response.body).isEqualTo(message)
    }

    private fun quantityInDatabase(skuId: String): Int =
        jdbcTemplate.queryForObject("SELECT quantity FROM inventory WHERE sku_id = ?", Int::class.java, skuId)!!

    private fun skuExists(skuId: String): Boolean =
        jdbcTemplate.queryForObject("SELECT count(*) FROM inventory WHERE sku_id = ?", Int::class.java, skuId)!! > 0

    companion object {
        private const val CLIENT_THREADS = 32
        private const val MALFORMED_BODY = "Malformed request body: quantity must be an integer between 1 and 2147483647"

        @JvmStatic
        fun invalidQuantityBodies(): List<Arguments> = listOf(
            Arguments.of("""{}""", "quantity is required"),
            Arguments.of("""{"quantity":null}""", "quantity is required"),
            Arguments.of("""{"quantity":0}""", "quantity must be greater than or equal to 1"),
            Arguments.of("""{"quantity":-5}""", "quantity must be greater than or equal to 1"),
            Arguments.of("""{"quantity":2.7}""", MALFORMED_BODY),
            Arguments.of("""{"quantity":"5"}""", MALFORMED_BODY),
            Arguments.of("""{"quantity":true}""", MALFORMED_BODY),
            Arguments.of("""{"quantity":3000000000}""", MALFORMED_BODY),
            Arguments.of("""{"quantity":""", MALFORMED_BODY),
            Arguments.of("", MALFORMED_BODY),
        )
    }
}
