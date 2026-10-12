# Inventory API — Technical Design Specification

| Field | Value |
|---|---|
| Service | `nuuly-inventory-api` |
| API contract | Inventory API 1.0.0 (OpenAPI 3.0.3) |
| Runtime | Kotlin 2.2 · Spring Boot 3.5 (Spring Web MVC) · JVM toolchain 17 |
| Persistence | PostgreSQL 15 · Spring Data JPA (Hibernate 6) · Flyway |
| Status | Approved for implementation |

This document defines the full architecture of the Inventory API before any application code is written. Every decision below is binding on the implementation; deviations require updating this spec first.

---

## 1. Scope and Contract Summary

| Operation | Method & Path | Success | Error responses |
|---|---|---|---|
| `getInventory` | `GET /inventory/{skuId}` | `200` `application/json` `InventoryItem` | `404` `text/plain` |
| `createInventory` | `POST /inventory/{skuId}` | `200` `application/json` `InventoryItem` | `400` `text/plain` |
| `purchaseItem` | `POST /inventory/{skuId}/purchase` | `200` `application/json` `InventoryItem` | `400` `text/plain`, `404` `text/plain` |
| `listInventory` | `GET /inventory` | `200` `application/json` `InventoryItem[]` | — |

Contract rules the implementation must honour exactly:

- `InventoryQuantity.quantity` is a required integer with `minimum: 1`.
- `InventoryItem.quantity` is an integer with `minimum: 0`.
- `POST /inventory/{skuId}` is an **additive upsert**: it creates the SKU with the given quantity, or adds the quantity to existing stock. It returns `200` in both cases (not `201`).
- `POST /inventory/{skuId}/purchase` is **all-or-nothing**: either the full requested quantity is deducted or nothing changes.
- All error bodies are a single human-readable string served as `text/plain` (the `Error` schema is `type: string`).
- `GET /inventory` returns `[]` when no SKUs exist, never `404`.

---

## 2. Architecture Overview

```
            ┌─────────────────────────── Load balancer ───────────────────────────┐
            │                                                                      │
   ┌────────▼────────┐        ┌─────────────────┐        ┌─────────────────┐
   │ inventory-api #1│        │ inventory-api #2│  ...   │ inventory-api #N│   stateless JVM replicas
   └────────┬────────┘        └────────┬────────┘        └────────┬────────┘
            │  HikariCP                │                          │
            └──────────────────────────┼──────────────────────────┘
                                       ▼
                         ┌───────────────────────────┐
                         │ PostgreSQL 15             │  single source of truth;
                         │  table: inventory         │  row-level locks serialize
                         └───────────────────────────┘  writes per SKU
```

Application replicas hold **no inventory state** (no caches, no in-memory counters, no JVM locks). All correctness guarantees come from PostgreSQL transactions, row locks and constraints, so the service scales horizontally without any coordination between replicas.

### 2.1 Package layout

```
com.nuuly.assessment.inventory
├── InventoryApplication.kt            @SpringBootApplication entry point
├── api
│   ├── InventoryController.kt         HTTP mapping only; no business logic
│   ├── dto
│   │   ├── InventoryQuantity.kt       request body (OpenAPI `InventoryQuantity`)
│   │   └── InventoryItem.kt           response body (OpenAPI `InventoryItem`)
│   └── exception
│       └── GlobalExceptionHandler.kt  @RestControllerAdvice → text/plain errors
├── domain
│   ├── InventoryEntity.kt             JPA entity mapped to `inventory`
│   └── exception
│       └── InventoryExceptions.kt     sealed InventoryException + SkuNotFound / InsufficientInventory / InvalidQuantity
├── service
│   └── InventoryService.kt            @Transactional business operations
└── persistence
    └── InventoryRepository.kt         Spring Data JPA + native upsert + locking query
```

Layering rule: `api` → `service` → `persistence`, with `domain` (entity and exceptions) shared by all three. Controllers never call repositories directly. Services never see HTTP types.

### 2.2 Build changes required

The current `build.gradle.kts` must be extended before implementation begins:

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    kotlin("plugin.jpa") version "2.2.20"          // no-arg constructors for @Entity
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")   // Jakarta Bean Validation
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")     // health/readiness probes
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

allOpen {
    annotation("jakarta.persistence.Entity")      // Hibernate proxies require open classes
}
```

---

## 3. Database Schema Layout

### 3.1 Migration ownership

Schema is owned exclusively by **Flyway**. Hibernate is set to `ddl-auto: validate` so the application refuses to start if the entity mapping and the real schema drift apart. Hibernate must never create or alter tables.

Migration file: `src/main/resources/db/migration/V1__create_inventory_table.sql`

```sql
-- V1__create_inventory_table.sql
-- Inventory on hand per SKU. One row per SKU; quantity can never go negative.

CREATE TABLE inventory (
    sku_id      VARCHAR(64) COLLATE "C" NOT NULL,
    quantity    INTEGER                 NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ             NOT NULL DEFAULT now(),

    CONSTRAINT pk_inventory
        PRIMARY KEY (sku_id),
    CONSTRAINT chk_inventory_quantity_non_negative
        CHECK (quantity >= 0),
    CONSTRAINT chk_inventory_sku_id_not_blank
        CHECK (length(btrim(sku_id)) > 0)
) WITH (fillfactor = 90);

COMMENT ON TABLE  inventory               IS 'Quantity on hand per SKU';
COMMENT ON COLUMN inventory.sku_id        IS 'Merchant SKU identifier, case-sensitive, max 64 chars';
COMMENT ON COLUMN inventory.quantity      IS 'Units on hand; enforced >= 0 at the data tier';
COMMENT ON COLUMN inventory.created_at    IS 'Row creation time (first stock-in)';
COMMENT ON COLUMN inventory.updated_at    IS 'Last quantity change, maintained by trigger';

CREATE FUNCTION inventory_set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_set_updated_at
    BEFORE UPDATE ON inventory
    FOR EACH ROW
    EXECUTE FUNCTION inventory_set_updated_at();
```

### 3.2 Column and constraint rationale

| Element | Decision | Reason |
|---|---|---|
| `sku_id VARCHAR(64)` primary key | Natural key, no surrogate ID | The API addresses rows only by SKU; a surrogate key would add an index and a join with no benefit. 64 characters covers retail SKU formats (e.g. `CW-XYCS-BM-01`) with headroom. |
| `COLLATE "C"` | Byte-wise collation | Makes SKU comparison exact and case-sensitive, makes `ORDER BY sku_id` deterministic across environments, and makes B-tree comparisons cheaper than locale-aware collations. |
| `quantity INTEGER` | 32-bit signed | Matches the OpenAPI `integer` type, which maps to Kotlin `Int`. The application guards against overflow on add (§5.3). |
| `CHECK (quantity >= 0)` | Enforced in the database | Last line of defence: even a buggy code path, a manual SQL fix or a future second writer cannot persist negative stock. The application checks first so this constraint should never fire in normal operation (§4.6). |
| `CHECK (length(btrim(sku_id)) > 0)` | No blank SKUs | Mirrors the `@NotBlank` API validation at the data tier. |
| `created_at` / `updated_at` | Audit timestamps, trigger-maintained | Operational debugging ("when did this SKU last move?") without trusting application clocks. They are not exposed in the API. |
| `fillfactor = 90` | Leave 10% free space per page | Quantity updates never touch an indexed column, so PostgreSQL can do **HOT (heap-only tuple) updates** in the same page. This cuts index churn and vacuum load on hot SKUs. |

No secondary indexes are needed. Every query is either a primary-key lookup or a full ordered scan (`listInventory`), and the primary-key B-tree serves the ordered scan.

### 3.3 JPA entity mapping

```kotlin
@Entity
@Table(name = "inventory")
class InventoryEntity(
    @Id
    @Column(name = "sku_id", length = 64, nullable = false, updatable = false)
    val skuId: String,

    @Column(name = "quantity", nullable = false)
    var quantity: Int,
)
```

`created_at` and `updated_at` are mapped **read-only** (`insertable = false, updatable = false`) as `Instant?` properties with protected setters. The database owns them through column defaults and the trigger. Hibernate reads them for auditing and logging but never writes them, so application clocks never overwrite database time. They are not exposed in the API.

The entity also carries Bean Validation constraints (`@NotBlank @Size(max = 64)` on `skuId`, `@Min(0)` on `quantity`) that mirror the CHECK constraints. Hibernate validates them before insert and update. `equals`/`hashCode` use `skuId` only, because it is an assigned natural key that never changes after construction.

---

## 4. Concurrency Isolation Strategy

### 4.1 The problem

`/purchase` is a read-check-write sequence:

1. Read the current `quantity`.
2. Verify `quantity >= requested`.
3. Write `quantity - requested`.

If two requests interleave between steps 1 and 3, both see the same starting stock, both pass the check and both write. That is a **lost update**, and it causes overselling. Example with 1 unit in stock and two concurrent purchases of 1:

```
T1: SELECT quantity → 1          T2: SELECT quantity → 1
T1: 1 >= 1 ✓                     T2: 1 >= 1 ✓
T1: UPDATE quantity = 0          T2: UPDATE quantity = 0
T1: COMMIT  → 200 OK             T2: COMMIT  → 200 OK      ← two units sold, one existed
```

### 4.2 Why in-memory JVM locks are rejected

`synchronized`, `ReentrantLock`, `ConcurrentHashMap<String, Lock>` (lock striping per SKU) and Kotlin `Mutex` are all **process-local**. They are disqualified for the following reasons:

| Failure mode | Explanation |
|---|---|
| **No cross-replica exclusion** | Production runs N replicas behind a load balancer. A lock in replica #1's heap means nothing to replica #2, so two purchases of the same SKU routed to different pods race exactly as in §4.1. The bug looks absent with one local instance and appears only under real load. |
| **No protection from other writers** | Restock jobs, admin scripts, data fixes and future services write to the same table without going through the JVM's lock. Only the database sees every writer. |
| **Lock scope ≠ transaction scope** | A JVM lock released before the database transaction commits (the natural shape with `@Transactional` proxies) re-opens the race window. Getting the ordering right is fragile and impossible to verify by review. |
| **Throughput collapse** | A single global lock serializes *all* SKUs. Per-SKU lock maps grow without bound, leak memory and still fail the cross-replica test. |
| **Thread starvation** | Blocked request threads hold Tomcat workers while they wait. Under a flash sale on one hot SKU, the whole pod stops serving unrelated traffic. |
| **Rolling deploys and crashes** | Lock state disappears on restart, and two versions of the service run side by side during a deploy. |

External distributed locks (Redis/Redlock, ZooKeeper) are also rejected. They add a second system whose failure modes (lease expiry during a GC pause, clock drift, network partition) can still allow double-writes, because the lock and the data live in different systems with no shared commit. PostgreSQL already owns the data, so it should also own the lock.

### 4.3 Chosen strategy: pessimistic row lock (`SELECT … FOR UPDATE`)

Each purchase runs in one database transaction that locks the SKU's row **before** reading the quantity it will check:

```sql
BEGIN;                                                    -- READ COMMITTED (PostgreSQL default)
SELECT sku_id, quantity FROM inventory
 WHERE sku_id = :skuId
   FOR UPDATE;                                            -- exclusive row lock, held until COMMIT/ROLLBACK
-- application: 0 rows → 404; quantity < requested → ROLLBACK + 400
UPDATE inventory SET quantity = quantity - :requested WHERE sku_id = :skuId;
COMMIT;                                                   -- lock released
```

The race from §4.1 now becomes:

```
T1: SELECT … FOR UPDATE → 1 (lock acquired)
                                 T2: SELECT … FOR UPDATE → blocks on T1's row lock
T1: 1 >= 1 ✓, UPDATE → 0, COMMIT (lock released)
                                 T2: unblocks, re-reads latest committed row → 0
                                 T2: 0 >= 1 ✗ → ROLLBACK → 400 "Insufficient inventory"
```

Properties this gives us:

- **Correct across any number of replicas.** The lock lives in PostgreSQL, which every replica and every other writer shares.
- **Per-SKU granularity.** Purchases of different SKUs never block each other; only same-SKU purchases are serialized, which is the minimum required for correctness.
- **Lock lifetime equals transaction lifetime.** The lock cannot be released before the write commits, and it is released automatically if the transaction rolls back, the connection drops or the pod crashes.
- **`READ COMMITTED` is sufficient.** In PostgreSQL, a `FOR UPDATE` that waited on another transaction re-evaluates against the newest committed version of the row. `SERIALIZABLE` isolation and its retry handling are not needed.
- **No deadlocks by construction.** Every transaction locks at most one row, so no transaction can hold one lock while waiting on another.

### 4.4 Why pessimistic over optimistic (`@Version`)

Optimistic locking lets all contenders proceed and fails all but one at commit. For retail inventory, contention concentrates on a few hot SKUs (drops, restocks of popular items). Under that load, optimistic locking turns into retry storms: every loser re-reads, re-validates and re-attempts, burning connections and adding tail latency, and the retry loop needs tuning for backoff and attempt limits. Pessimistic locking queues contenders in the database and serves each exactly once. Lock hold time is a single primary-key update (sub-millisecond), so the queue drains fast.

A single conditional statement (`UPDATE inventory SET quantity = quantity - :q WHERE sku_id = :id AND quantity >= :q RETURNING quantity`) is also race-free, because PostgreSQL row-locks during the update. It was considered and not chosen as the primary mechanism, because it cannot tell "SKU not found" (404) from "insufficient stock" (400) without a second query, and the project guidelines require explicit pessimistic locking. It remains a valid optimisation if profiling ever shows the extra round trip matters.

### 4.5 Implementation binding

**Repository**

```kotlin
interface InventoryRepository : JpaRepository<InventoryEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)                 // Hibernate 6 emits SELECT … FOR NO KEY UPDATE
    @Query("select i from InventoryEntity i where i.skuId = :skuId")
    fun findByIdForUpdate(@Param("skuId") skuId: String): InventoryEntity?

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

    fun findAllByOrderBySkuIdAsc(): List<InventoryEntity>
}
```

**Service**

```kotlin
@Service
class InventoryService(private val repository: InventoryRepository) {

    @Transactional(readOnly = true)
    fun get(skuId: String): InventoryEntity =
        repository.findByIdOrNull(skuId) ?: throw SkuNotFoundException(skuId)

    @Transactional(readOnly = true)
    fun list(): List<InventoryEntity> = repository.findAllByOrderBySkuIdAsc()

    @Transactional
    fun addStock(skuId: String, quantity: Int): InventoryEntity {
        val affected = repository.upsertAddQuantity(skuId, quantity)
        if (affected == 0) throw InvalidQuantityException("Resulting quantity exceeds maximum of 2147483647")
        // Row is now locked by this transaction until commit; this read sees our own write.
        return repository.findByIdOrNull(skuId)!!
    }

    @Transactional
    fun purchase(skuId: String, quantity: Int): InventoryEntity {
        val item = repository.findByIdForUpdate(skuId) ?: throw SkuNotFoundException(skuId)
        if (item.quantity < quantity) throw InsufficientInventoryException(skuId, quantity, item.quantity)
        item.quantity -= quantity                       // flushed as UPDATE on commit via dirty checking
        return item
    }
}
```

The exceptions extend the sealed `InventoryException` (a `RuntimeException` that skips stack-trace capture), so Spring rolls the transaction back and releases the row lock immediately. The exception `message` is the exact `text/plain` body returned to the client.

### 4.6 Concurrency on the create/restock path

A naive "find, then insert if missing" for `POST /inventory/{skuId}` has its own race: two concurrent first-time stock-ins of a new SKU both see "missing", both `INSERT`, and one fails with a primary-key violation. `FOR UPDATE` cannot help here, because there is no row to lock yet.

`INSERT … ON CONFLICT (sku_id) DO UPDATE` solves this atomically inside PostgreSQL:

- If the row doesn't exist, it is inserted. A concurrent inserter waits on the unique-index entry and then takes the `DO UPDATE` branch.
- If the row exists, the update takes the same row lock as `FOR UPDATE`, so restocks and purchases of the same SKU serialize against each other.
- Quantity is added relative to the **current** value (`inventory.quantity + EXCLUDED.quantity`), never as a value computed in the JVM, so concurrent restocks are never lost.
- The `WHERE inventory.quantity <= 2147483647 - EXCLUDED.quantity` guard makes the statement affect 0 rows instead of raising `integer out of range`. The service turns that into a `400` (§5.3).

### 4.7 Lock wait and timeout policy

An unbounded lock wait would let a stuck transaction pin every request thread for a hot SKU. Every connection is therefore opened with server-side timeouts:

| Setting | Value | Effect |
|---|---|---|
| `lock_timeout` | `3000 ms` | A `FOR UPDATE` or upsert that waits longer than 3 s fails with SQLSTATE `55P03`, which Spring translates to `CannotAcquireLockException`. |
| `statement_timeout` | `5000 ms` | Any statement running longer than 5 s is cancelled (SQLSTATE `57014` → `QueryTimeoutException`). |
| `idle_in_transaction_session_timeout` | `10000 ms` | Kills sessions that hold locks while idle, for example after an application bug. |

These are applied through the PostgreSQL JDBC `options` connection property (see §6), so they cover every pooled connection without per-query code. Lock and timeout failures map to `503 Service Unavailable` with a `Retry-After: 1` header (§5.4). The request had no effect, and the client may safely retry.

### 4.8 Transaction hygiene rules

1. `spring.jpa.open-in-view=false`: connections and locks must never stay open while the response is serialized.
2. Transactions span **only** database work. No HTTP calls, message publishing or sleeps inside a `@Transactional` method that holds a row lock.
3. `@Transactional` lives on service methods only, never on controllers or repositories.
4. Read-only endpoints use `@Transactional(readOnly = true)` and never take locks. Under MVCC, `GET` requests never block behind purchases.
5. Connection pool size (`maximum-pool-size: 20` per replica) times the replica count must stay below PostgreSQL `max_connections` minus the admin reserve. Revisit this when scaling replicas.

### 4.9 Data-tier defence in depth

If any path ever tries to persist `quantity < 0`, the CHECK constraint `chk_inventory_quantity_non_negative` rejects the write with SQLSTATE `23514`, which Spring translates to `DataIntegrityViolationException`. The handler maps this to `400 Insufficient inventory` and logs it at `ERROR`, because reaching it means an application-level check was bypassed and needs investigation.

---

## 5. API Controller and Status Code Mapping

### 5.1 Controller contract

```kotlin
@RestController
@RequestMapping("/inventory")
class InventoryController(private val service: InventoryService) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listInventory(): List<InventoryItem>

    @GetMapping("/{skuId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getInventory(@PathVariable skuId: String): InventoryItem   // no constraints: GET allows only 200/404

    @PostMapping(
        "/{skuId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createInventory(
        @PathVariable @NotBlank @Size(max = 64) skuId: String,
        @Valid @RequestBody request: InventoryQuantity,
    ): InventoryItem

    @PostMapping(
        "/{skuId}/purchase",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun purchaseItem(
        @PathVariable @NotBlank @Size(max = 64) skuId: String,
        @Valid @RequestBody request: InventoryQuantity,
    ): InventoryItem
}
```

The controller class is **not** annotated with `@Validated`. Spring Framework 6.1+ applies built-in method validation when parameters carry constraint annotations, and reports all failures (path and body) as a single `HandlerMethodValidationException`. Adding `@Validated` would switch to the AOP interceptor and produce `ConstraintViolationException` instead. Only one mechanism may be active, and this spec chooses the built-in one.

### 5.2 DTOs

```kotlin
data class InventoryQuantity(
    @field:NotNull(message = "quantity is required")
    @field:Min(value = 1, message = "quantity must be greater than or equal to 1")
    val quantity: Int?,
)

data class InventoryItem(
    val skuId: String,
    val quantity: Int,
) {
    companion object {
        fun from(entity: InventoryEntity) = InventoryItem(entity.skuId, entity.quantity)
    }
}
```

`quantity` is declared **nullable** (`Int?`) on purpose. With a non-null `Int`, a missing field fails inside Jackson's Kotlin module as a `MismatchedInputException` before Bean Validation runs, which produces a different exception type and an unhelpful message. Nullable plus `@NotNull` sends "missing" and "explicit `null`" through the same validation path with a clear message. After validation the service receives a guaranteed non-null value (`request.quantity!!`).

### 5.3 Strict JSON deserialization

Jackson's defaults are too lenient for a stock-movement API. Each of these defaults would silently accept a malformed quantity:

| Input | Jackson default | Required behaviour | Setting |
|---|---|---|---|
| `{"quantity": 2.7}` | Truncated to `2` | `400` | `spring.jackson.deserialization.accept-float-as-int: false` |
| `{"quantity": "5"}` | Coerced to `5` | `400` | `spring.jackson.mapper.allow-coercion-of-scalars: false` |
| `{"quantity": 3000000000}` | Overflow error | `400` | Default `InputCoercionException` → `HttpMessageNotReadableException` |
| `{"quantity": 5, "foo": 1}` | Ignored | Ignored (tolerant reader) | Spring Boot default `fail-on-unknown-properties: false` retained |

Additive overflow (existing stock + requested quantity > 2,147,483,647) is detected by the upsert guard in §4.6 and raised as `InvalidQuantityException` → `400`.

### 5.4 Exception → HTTP status → `text/plain` body mapping

All errors are produced by a single `@RestControllerAdvice` (`GlobalExceptionHandler`). Every handler returns `ResponseEntity<String>` with `Content-Type: text/plain;charset=UTF-8` set explicitly. Setting the content type explicitly makes Spring skip content negotiation, so a client sending `Accept: application/json` still receives the `text/plain` error required by the contract instead of a `406`.

| # | Trigger | Exception caught | Status | Body (exact text) |
|---|---|---|---|---|
| 1 | SKU not in `inventory` on GET or purchase | `SkuNotFoundException` | `404` | `SKU not found: {skuId}` |
| 2 | Purchase quantity exceeds stock on hand | `InsufficientInventoryException` | `400` | `Insufficient inventory` |
| 3 | `quantity` missing or `null` | `HandlerMethodValidationException` / `MethodArgumentNotValidException` | `400` | `quantity is required` |
| 4 | `quantity` < 1 (zero or negative) | `HandlerMethodValidationException` / `MethodArgumentNotValidException` | `400` | `quantity must be greater than or equal to 1` |
| 5 | `skuId` blank or longer than 64 chars on a POST route (a GET lookup of such an id returns `404`, the only error the contract allows for GET) | `HandlerMethodValidationException` | `400` | `skuId must be between 1 and 64 characters` |
| 6 | Body missing, not valid JSON, `quantity` not an integer (float, string, boolean, out of 32-bit range) | `HttpMessageNotReadableException` | `400` | `Malformed request body: quantity must be an integer between 1 and 2147483647` |
| 7 | Restock would push stock past 2,147,483,647 | `InvalidQuantityException` | `400` | `Resulting quantity exceeds maximum of 2147483647` |
| 8 | CHECK constraint `chk_inventory_quantity_non_negative` fired (defence in depth) | `DataIntegrityViolationException` (SQLSTATE `23514`) | `400` | `Insufficient inventory` (logged at `ERROR`) |
| 9 | `Content-Type` not `application/json` on POST | `HttpMediaTypeNotSupportedException` | `415` | `Content-Type must be application/json` |
| 10 | Unsupported HTTP method (e.g. `DELETE /inventory/x`) | `HttpRequestMethodNotSupportedException` | `405` | `Method {method} not allowed` (+ `Allow` header) |
| 11 | Unknown path | `NoResourceFoundException` | `404` | `Not found` |
| 12 | Row-lock wait exceeded `lock_timeout`, deadlock, or statement timeout | `PessimisticLockingFailureException`, `QueryTimeoutException` | `503` | `Inventory is busy, please retry` (+ `Retry-After: 1`) |
| 13 | Any other unhandled exception | `Exception` | `500` | `Internal server error` (stack trace logged, never returned) |

Rows 1–8 are within the published contract. Rows 9–13 are infrastructure responses outside the OpenAPI document. They still use `text/plain`, so clients only ever deal with one error format.

When several validation errors occur in one request, the handler returns the **first** message in this fixed order: `skuId`, then `quantity` null, then `quantity` range. This keeps responses deterministic for clients and tests.

To route rows 9–11 through the advice instead of Spring Boot's JSON `/error` page, `GlobalExceptionHandler` handles these exceptions explicitly. It does not extend `ResponseEntityExceptionHandler`, whose built-in handlers emit RFC 7807 `application/problem+json`.

### 5.5 Evaluation order per endpoint

The order determines which error a client sees when a request is wrong in more than one way.

**`POST /inventory/{skuId}/purchase`**

1. Content-Type check → `415`
2. JSON parse → `400` (row 6)
3. Bean Validation on `skuId` and body → `400` (rows 3–5)
4. Begin transaction; `SELECT … FOR UPDATE` → `404` if no row (row 1); `503` if the lock wait times out (row 12)
5. Stock check → `400 Insufficient inventory` (row 2)
6. Decrement, commit → `200` with remaining `InventoryItem`

A purchase of an unknown SKU with an invalid body therefore returns `400`, not `404`. Input is validated before any database work.

**`POST /inventory/{skuId}`**

1. Content-Type check → `415`
2. JSON parse → `400`
3. Bean Validation → `400`
4. Upsert; 0 affected rows → `400` (row 7)
5. Re-read the row in the same transaction, commit → `200` with the updated `InventoryItem`

**`GET /inventory/{skuId}`**: lookup → `404` or `200`. There is no path validation, because the contract defines only `200` and `404` for this operation; a blank or over-long SKU simply does not exist.

**`GET /inventory`**: always `200`; ordered by `sku_id` ascending (byte order, per `COLLATE "C"`); `[]` when empty.

### 5.6 Example exchanges

```http
POST /inventory/CW-XYCS-BM-01 HTTP/1.1
Content-Type: application/json

{"quantity": 10}

HTTP/1.1 200 OK
Content-Type: application/json

{"skuId":"CW-XYCS-BM-01","quantity":10}
```

```http
POST /inventory/CW-XYCS-BM-01/purchase HTTP/1.1
Content-Type: application/json

{"quantity": 11}

HTTP/1.1 400 Bad Request
Content-Type: text/plain;charset=UTF-8

Insufficient inventory
```

```http
POST /inventory/CW-XYCS-BM-01/purchase HTTP/1.1
Content-Type: application/json

{"quantity": -3}

HTTP/1.1 400 Bad Request
Content-Type: text/plain;charset=UTF-8

quantity must be greater than or equal to 1
```

```http
GET /inventory/does-not-exist HTTP/1.1

HTTP/1.1 404 Not Found
Content-Type: text/plain;charset=UTF-8

SKU not found: does-not-exist
```

---

## 6. Application Configuration

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: nuuly-inventory-api
  config:
    import: optional:file:.env[.properties]     # reuse docker-compose credentials locally; absent in prod

  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    hikari:
      pool-name: inventory-pool
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 2000
      data-source-properties:
        options: "-c lock_timeout=3000 -c statement_timeout=5000 -c idle_in_transaction_session_timeout=10000"

  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC

  flyway:
    enabled: true
    locations: classpath:db/migration

  jackson:
    deserialization:
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false

server:
  error:
    include-stacktrace: never
    include-message: never

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
```

Credentials follow the `.env` approach in `setup.md`. Locally, Spring imports the git-ignored `.env` file directly. In deployed environments, the same variable names come from the platform's secret store, and no credential ever appears in source control.

---

## 7. Verification Strategy

All persistence and concurrency tests run against **real PostgreSQL via Testcontainers**. H2 is not used anywhere, because its locking semantics, `ON CONFLICT` support and CHECK behaviour differ from PostgreSQL's.

| Layer | Test class | What it proves |
|---|---|---|
| Web | `InventoryControllerTest` (`@WebMvcTest`, mocked service) | Every row of the §5.4 table: status code, `text/plain` content type, exact body text. Includes floats, strings, `null`, missing body, `0`, negative values, a 65-char SKU, wrong `Content-Type`, and `Accept: application/json` on error paths. |
| Persistence | `InventoryRepositoryTest` (`@DataJpaTest` + Testcontainers) | Flyway migration applies; `ddl-auto=validate` passes; CHECK rejects `quantity = -1`; upsert creates, adds, and returns 0 rows on overflow; `findAllByOrderBySkuIdAsc` ordering. |
| Concurrency | `PurchaseConcurrencyIT` (`@SpringBootTest` + Testcontainers) | Seed SKU with 100 units; fire 250 parallel purchases of 1 unit from a fixed thread pool released by a shared `CountDownLatch`. Assert exactly 100 × `200`, 150 × `400 Insufficient inventory`, final quantity `0`, and the sum of the remaining quantities in the 200 responses equals 0+1+…+99. |
| Concurrency | `RestockConcurrencyIT` | 50 parallel first-time `POST /inventory/NEW-SKU {"quantity":2}` → all `200`, final quantity `100`, no primary-key violations. |
| Concurrency | `MixedLoadIT` | Interleaved restocks and purchases on one SKU; invariant `initial + Σrestocked − Σpurchased(200) = final` and `final ≥ 0`. |
| Multi-instance | `MultiReplicaPurchaseIT` | Two Spring application contexts on different ports sharing one Testcontainers database; repeat the 100-unit/250-request scenario split across both. This proves correctness does not depend on JVM-local state. |

The concurrency suites are the acceptance gate for §4: a design that only passes single-instance tests does not meet this spec.

---

## 8. Operational Considerations

- **Observability:** Log every `4xx` at `DEBUG` and every `5xx` at `ERROR` with the SKU and the request's trace ID. Track the Micrometer metric `inventory.purchase` tagged by `outcome` (`success`, `insufficient`, `not_found`, `lock_timeout`). A rising `lock_timeout` rate is the signal that one SKU's contention is exceeding single-row throughput.
- **Hot-SKU ceiling:** Serializing on one row caps a single SKU at roughly the inverse of lock hold time (a few thousand purchases per second on a primary-key update). If a future flash-sale requirement exceeds that, the next step is splitting a SKU's stock into N bucket rows. That is a schema change and must be specified in a new revision of this document.
- **List growth:** `GET /inventory` is unpaginated per the 1.0.0 contract. Once the catalogue grows beyond a few thousand SKUs, a v1.1 contract should add cursor pagination (`?after={skuId}&limit=`), which the `sku_id` primary-key ordering already supports.
- **Health:** `/actuator/health/readiness` includes the datasource check, so replicas that lose PostgreSQL connectivity leave the load balancer rotation instead of returning `500`s.
