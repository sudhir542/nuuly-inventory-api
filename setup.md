# Phase 1: Environment Initialization & Configuration Log

This document records the exact steps, terminal commands, and structural decisions executed to initialize the codebase for the Nuuly Services Assessment.

---

## 1. Directory and Git Initialization
**Action:** Established a dedicated local workspace and initialized version control.

```bash
# Create and move into the project root directory
mkdir nuuly-inventory-api
cd nuuly-inventory-api

# Initialize Git version control
git init

# Establish an explicit repository folder to commit and track AI interactions,
# prompts, configurations, and plans per Nuuly's workflow guidelines
mkdir .ai-artifacts
```

---

## 2. Setting AI Workspace Constraints
**Action:** Created the AI governance instructions file `.ai-artifacts/ai-instructions.md` to enforce strict technological boundaries on the LLM tooling before generating any application code. Keeping it in `.ai-artifacts/` versions the instructions alongside the prompts and plans tracked there, instead of in a tool-specific root file such as `.cursorrules`.

**Key Constraints Injected:**
* Enforced **Kotlin** (JVM) and **Spring Boot 3.x** to directly match Nuuly's primary platform architecture.
* Mandated an atomized, step-driven code output strategy (one file at a time) to allow manual peer-review cycles before progressing.
* Explicitly prohibited in-memory locking patterns, demanding production-ready database level thread safety (pessimistic locks) to properly support distributed systems.

---

## 3. Local Infrastructure Stack Setup
**Action:** Constructed a standard `docker-compose.yml` to provision a isolated, localized database environment. This satisfies the requirement of launching the complete platform seamlessly with minimal host setup.

```yaml
services:
  postgres:
    image: postgres:15-alpine
    container_name: nuuly-inventory-db
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?set in .env}
      POSTGRES_USER: ${POSTGRES_USER:?set in .env}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

### Database Credentials (`.env`)
**Action:** Moved the database name, username, and password out of `docker-compose.yml` into a local `.env` file so credentials are never committed. Docker Compose loads `.env` from the project root automatically, and the `:?` syntax makes `docker compose up` fail fast if any value is missing.

* `.env` — real local values; listed in `.gitignore` and never checked in.
* `.env.example` — committed template with the same keys and working local-only default credentials (see Phase 9.2), so a fresh clone only needs `cp`.

```bash
# Create your local .env from the template (the defaults work as-is for local development)
cp .env.example .env

# Confirm git ignores it
git check-ignore -v .env
```

---

## 4. Launching & Base Verification
**Action:** Executed local infrastructure orchestration commands to confirm environment viability before writing the software layer.

```bash
# Spin up the PostgreSQL microservice stack background daemon (requires .env from step 3)
docker compose up -d

# Verify container connectivity and port assignment
docker ps
```

---

# Phase 2: Spec-Driven Architecture & `tech-spec.md`

## 2.1 Toolchain Stabilization
**Action:** Before any design work, repaired the Gradle build so the project could sync in IntelliJ IDEA and from the command line.

| Problem | Symptom | Resolution |
|---|---|---|
| Gradle JVM set to Java 27 | `Unsupported class file major version 71`; IntelliJ reported Java 27 is incompatible with Gradle 9.6.0 | Set IntelliJ's Gradle JVM to the installed JDK 25 (Temurin) in `.idea/gradle.xml`; documented `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` for terminal use |
| Plugins predating Gradle 9 | `NoSuchMethodError: LenientConfiguration.getArtifacts(Spec)` during dependency resolution | Kotlin 1.9.22 → 2.2.20, Spring Boot 3.2.3 → 3.5.6, `io.spring.dependency-management` 1.1.4 → 1.1.7 |
| Typo in a dependency coordinate | `springboot-starter-web` could never resolve | Corrected to `spring-boot-starter-web` |

## 2.2 Technical Design Specification
**Action:** Reviewed the Inventory API OpenAPI 3.0.3 contract and produced `tech-spec.md`, a binding design document written before any application code. Any later deviation required updating the spec first.

**Sections Defined:**
1. **Scope and contract summary:** the four operations, their success and error responses, and contract rules that are easy to get wrong. For example, `POST /inventory/{skuId}` is an additive upsert returning `200` (not `201`), purchases are all-or-nothing, and every error body is a single `text/plain` string.
2. **Architecture overview:** stateless JVM replicas behind a load balancer with PostgreSQL as the single source of truth. Package layout and layering rules (`api` → `service` → `persistence`, with `domain` shared).
3. **Database schema layout:** the `inventory` table, every constraint, and the rationale for each (detailed in Phase 3).
4. **Concurrency isolation strategy:** the oversell race, why JVM-local locks and external distributed locks were rejected, the pessimistic `SELECT … FOR UPDATE` design, why pessimistic beats optimistic `@Version` locking for hot SKUs, the atomic upsert for restocks, and lock timeout policy.
5. **API controller and status code mapping:** controller signatures, DTOs, strict JSON deserialization, the exception → status → `text/plain` body table, and the per-endpoint order in which checks run.
6. **Application configuration:** the full `application.yml` (detailed in Phase 4).
7. **Verification strategy:** Testcontainers against real PostgreSQL, never H2, because H2's locking, `ON CONFLICT`, and CHECK behaviour differ from PostgreSQL's. Concurrency tests are the acceptance gate.
8. **Operational considerations:** observability, the single-row throughput ceiling for one hot SKU, list pagination for a future contract version, and readiness probes.

**Key Decisions:**
* **Pessimistic over optimistic locking.** Retail contention concentrates on a few hot SKUs. Optimistic locking turns that into retry storms; pessimistic locking queues contenders in the database and serves each exactly once, with a lock hold time of a single primary-key update.
* **Single conditional `UPDATE … WHERE quantity >= ?` recorded as an alternative, not chosen.** It is race-free but cannot distinguish "SKU not found" (`404`) from "insufficient stock" (`400`) without a second query, and the governance instructions require explicit pessimistic locking.
* **Spec revisions during implementation**, each applied to `tech-spec.md` as it happened:
  * Audit timestamps mapped read-only on the entity.
  * One exceptions file, with `InvalidQuantityException` replacing `QuantityOverflowException`.
  * The service moved to its own `service` package.
  * DTOs renamed `InventoryQuantity` / `InventoryItem` to match the OpenAPI schemas.
  * The advice renamed `GlobalExceptionHandler` under `api/exception`.
  * The lock comment corrected to Hibernate's actual `FOR NO KEY UPDATE`.

---

# Phase 3: Flyway Migration & Repository Mapping

## 3.1 Flyway Migration Script
**Action:** Created `src/main/resources/db/migration/V1__create_inventory_table.sql`. Flyway is the only owner of the schema; Hibernate never creates or alters tables.

```sql
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
```

A `BEFORE UPDATE` trigger (`trg_inventory_set_updated_at`, backed by the `inventory_set_updated_at()` PL/pgSQL function) sets `updated_at = now()` on every change. `COMMENT ON` statements document the table and each column in the database itself.

**Key Decisions:**
* **`sku_id VARCHAR(64)` as a natural primary key.** The API only addresses rows by SKU, so a surrogate ID would add an index and a join with no benefit. 64 characters covers retail SKU formats such as `CW-XYCS-BM-01` with headroom.
* **`COLLATE "C"` (byte-wise collation).** SKU comparison is exact and case-sensitive (`widget` and `Widget` are different SKUs), `ORDER BY sku_id` gives the same result in every environment regardless of OS locale, and B-tree comparisons are cheaper than with locale-aware collations.
* **`CHECK (quantity >= 0)` at the data tier.** The last line of defence: even a buggy code path, a manual SQL fix, or a future second writer cannot persist negative stock. The application checks first, so this constraint should never fire in normal operation.
* **`CHECK (length(btrim(sku_id)) > 0)`.** Mirrors the API's `@NotBlank` rule so whitespace-only SKUs cannot exist.
* **`fillfactor = 90`.** By default PostgreSQL packs heap pages 100% full. Every purchase and restock rewrites the row (MVCC creates a new tuple version), and if the page has no room the new version goes to another page. Every index on the table then needs a new entry, and vacuum has more to clean up. Leaving 10% of each page free lets PostgreSQL perform **HOT (heap-only tuple) updates**: the new row version is written to the same page and, because `quantity` is not indexed, the primary-key index is not touched at all. For a table whose workload is almost entirely updates to the same small set of hot rows, this cuts index churn and vacuum load at the cost of about 10% more disk.
* **Trigger-maintained timestamps.** `updated_at` is set by the database, so audit times never depend on application server clocks and cannot be overwritten by stale entity state.
* **No secondary indexes.** Every query is either a primary-key lookup or an ordered scan of all rows, and the primary-key B-tree serves both.

## 3.2 Spring Data JPA Repository
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/persistence/InventoryRepository.kt`, extending `JpaRepository<InventoryEntity, String>` for standard CRUD.

| Method | Purpose |
|---|---|
| `findByIdForUpdate(skuId)` | `@Lock(LockModeType.PESSIMISTIC_WRITE)` JPQL query; loads the SKU and takes an exclusive row lock held until the transaction ends. Returns `null` if the SKU does not exist. |
| `upsertAddQuantity(skuId, quantity)` | Native `INSERT … ON CONFLICT (sku_id) DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity WHERE inventory.quantity <= 2147483647 - EXCLUDED.quantity`. Returns rows written: `1` on success, `0` if the addition would overflow. |
| `findAllByOrderBySkuIdAsc()` | All SKUs in byte-wise order for `GET /inventory`. |

**Key Decisions:**
* **Atomic upsert for restocks.** A "find, then insert if missing" approach fails when two requests create the same new SKU at once: both see it missing, both insert, one hits a primary-key violation. `FOR UPDATE` cannot help because there is no row to lock yet. `ON CONFLICT` resolves the race inside PostgreSQL, and the addition is computed from the row's current value, so concurrent restocks are never lost.
* **Overflow guard in SQL.** The `WHERE` clause on the conflict branch makes the statement write 0 rows instead of raising `integer out of range`, giving the service a clean signal to return a `400`.
* **`@Modifying(flushAutomatically = true, clearAutomatically = true)`** on the upsert. Pending entity changes are flushed before the native SQL runs, and the persistence context is cleared afterwards, so the read that follows returns the new quantity rather than a stale cached copy.
* **Placed in `src/main/kotlin/…/persistence/`**, the standard Kotlin source folder and the package defined in the spec.

## 3.3 Verification
Applied the migration to a throwaway local PostgreSQL and exercised every constraint:

| Check | Result |
|---|---|
| Migration applies cleanly | Passed |
| Upsert: create (10), add (5), overflow add (2147483647) | 1 row, 1 row, 0 rows; quantity remained 15 |
| `updated_at` trigger fires on update | `updated_at > created_at` |
| `quantity = -1` | Rejected by `chk_inventory_quantity_non_negative` |
| Whitespace-only SKU | Rejected by `chk_inventory_sku_id_not_blank` |
| 65-character SKU | Rejected: `value too long for type character varying(64)` |
| Case sensitivity and ordering | `Widget`, `a-sku`, `widget` stored separately and sorted byte-wise as `Widget,a-sku,widget` |

---

# Phase 4: Build Configuration, Entity Mapping & Application Environment

## 4.1 `build.gradle.kts`
**Action:** Extended the build script with the plugins and dependencies the design requires. All library versions are managed by the Spring Boot dependency-management BOM.

| Addition | Reason |
|---|---|
| `kotlin("plugin.jpa")` | Generates the no-argument constructors Hibernate needs to instantiate entities. |
| `allOpen` for `@Entity`, `@MappedSuperclass`, `@Embeddable` | Kotlin classes are final by default; Hibernate creates runtime subclasses (proxies) of entities, which requires them to be open. Spring beans are already handled by `plugin.spring`. |
| `spring-boot-starter-validation` | Jakarta Bean Validation for request bodies and path variables. |
| `flyway-core`, `flyway-database-postgresql` | Runs the migration on startup. Flyway 10+ ships PostgreSQL support as a separate module. |
| `spring-boot-starter-actuator` | Health, liveness, and readiness endpoints. |
| `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:postgresql` | Integration tests against real PostgreSQL. |
| `-Xjsr305=strict` | Makes Kotlin honour Spring's nullability annotations, so Spring APIs that may return null surface as nullable Kotlin types. |
| `-Xannotation-default-target=param-property` | Under Kotlin 2.2, makes annotations on constructor properties apply to both the parameter and the property, the behaviour JPA and validation annotations expect. |
| `tasks.withType<Test> { useJUnitPlatform() }` | Runs JUnit 5 tests. |

`kotlin { jvmToolchain(17) }` is unchanged. The `foojay-resolver-convention` plugin in `settings.gradle.kts` downloads JDK 17 automatically if it is not installed.

## 4.2 JPA Entity
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/domain/InventoryEntity.kt` mapped to the `inventory` table.

**Key Decisions:**
* **`skuId` is the `@Id`** with `updatable = false`, plus `@field:NotBlank` and `@field:Size(max = 64)` mirroring the column and its CHECK constraint.
* **`quantity` carries `@field:Min(0)`.** Hibernate validates before every insert and update, so an invalid row is rejected in the JVM before reaching the database, while the CHECK constraint remains the final authority.
* **Audit timestamps mapped read-only.** `createdAt` and `updatedAt` are `Instant?` properties with `insertable = false, updatable = false` and protected setters. The database owns them through column defaults and the trigger; Hibernate reads them for auditing but never writes application-clock values over them.
* **`equals` / `hashCode` on `skuId` only.** It is an assigned natural key fixed at construction, so identity is stable across new, managed, and detached states and through proxies. A Kotlin `data class` was avoided because its generated `equals`, `hashCode`, and `toString` include every field, which breaks JPA identity semantics and can trigger lazy loading.

## 4.3 `application.yml`
**Action:** Created `src/main/resources/application.yml`.

**Datasource and credentials:**
* The JDBC URL is assembled from `POSTGRES_HOST`, `POSTGRES_PORT`, and `POSTGRES_DB` (defaults `localhost`, `5432`, `inventory_db`).
* `POSTGRES_USER` and `POSTGRES_PASSWORD` have no defaults, so startup fails if credentials are not supplied.
* `spring.config.import: optional:file:.env[.properties]` reads the same git-ignored `.env` file Docker Compose uses. Local runs need no exported variables; deployed environments have no `.env` and supply the variables from their secret store.

**Connection pool and database timeouts:**
* HikariCP with `maximum-pool-size: 20`, `minimum-idle: 5`, and `connection-timeout: 2000` ms.
* Every pooled connection is opened with `options: "-c lock_timeout=3000 -c statement_timeout=5000 -c idle_in_transaction_session_timeout=10000"`:
  * A purchase waiting on a locked row gives up after 3 seconds.
  * Any statement is cancelled after 5 seconds.
  * A session that sits idle inside a transaction while holding locks is terminated after 10 seconds.
* Applying these through the JDBC `options` property covers every connection without per-query code.

**JPA and schema:**
* **`ddl-auto: validate`.** Flyway owns the schema. Hibernate is restricted to comparing the entity mapping against the real tables at startup and refusing to start on any mismatch (a missing column, a wrong type, a renamed table). The alternatives were rejected:
  * `update` silently alters production tables and can never drop or rename safely.
  * `create` / `create-drop` destroy data.
  * `none` lets a mapping/schema drift surface only at runtime as SQL errors.

  `validate` turns drift into an immediate, loud deployment failure.
* **`open-in-view: false`.** Spring Boot's default keeps a database connection (and any locks it holds) open until the HTTP response has been written. Disabling it confines connections to the `@Transactional` service methods, so row locks are held only for the database work itself.
* **`hibernate.jdbc.time_zone: UTC`** for consistent timestamp handling.

**Jackson:** `accept-float-as-int: false` and `allow-coercion-of-scalars: false` reject floats and numeric strings instead of truncating or coercing them (detailed in Phase 7.3).

**Server and Actuator:**
* `server.shutdown: graceful` with a 20-second phase timeout lets in-flight requests finish during a redeploy.
* `server.error.include-message`, `include-binding-errors`, `include-stacktrace`, and `include-exception` are all disabled, so Spring's default error output never leaks internals.
* Only the `health` endpoint is exposed over HTTP, with `show-details: never`.
* Liveness and readiness probes are enabled, and the readiness group includes the `db` check, so a replica that loses PostgreSQL is taken out of load-balancer rotation.

## 4.4 Verification
`./gradlew compileKotlin` succeeded with no warnings. Booted the full Spring context against a throwaway local PostgreSQL:

| Check | Result |
|---|---|
| Flyway migration | `V1` applied, `success = true` |
| `ddl-auto: validate` | Entity mapping matched the schema; context started |
| Per-connection timeouts (`SHOW …`) | `lock_timeout = 3s`, `statement_timeout = 5s`, `idle_in_transaction_session_timeout = 10s` |
| `{"quantity":5}` / `2.7` / `"5"` / `3000000000` | Accepted / `InvalidFormatException` / `MismatchedInputException` / `JsonMappingException` |
| `/actuator/health` | `200 {"status":"UP","groups":["liveness","readiness"]}` |
| `/actuator/health/readiness` | `200 {"status":"UP"}` |
| `/actuator/env` | `404` (not exposed) |

---

# Phase 5: Application Entry Point & Domain Exceptions

## 5.1 Spring Boot Entry Point
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/InventoryApplication.kt`, a standard `@SpringBootApplication` class with a `runApplication` main function.

**Key Decisions:**
* Placed in the root package `com.nuuly.assessment.inventory` so component scanning, JPA entity scanning, and Spring Data repository detection cover every sub-package (`api`, `service`, `domain`, `persistence`) without any explicit `@EntityScan` or `@EnableJpaRepositories` configuration.

## 5.2 Domain Exception Framework
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/domain/exception/InventoryExceptions.kt` containing a sealed exception hierarchy for business-rule failures.

| Exception | Raised when | HTTP mapping | Client message |
|---|---|---|---|
| `SkuNotFoundException(skuId)` | GET or purchase targets a SKU with no row | `404` | `SKU not found: {skuId}` |
| `InsufficientInventoryException(skuId, requested, available)` | Purchase quantity exceeds stock on hand | `400` | `Insufficient inventory` |
| `InvalidQuantityException(message)` | A stock operation breaches quantity bounds (below 1, or a restock past the 32-bit INTEGER maximum) | `400` | The supplied message |

**Key Decisions:**
* All three extend `sealed class InventoryException : RuntimeException`. Being unchecked, any of them rolls back the surrounding `@Transactional` method and releases the PostgreSQL row lock immediately.
* Stack-trace capture is disabled (`RuntimeException(message, null, false, false)`). These are expected business outcomes, not programming errors; under flash-sale load the insufficient-inventory path can fire thousands of times per second, and filling a stack trace on each one is wasted CPU with no diagnostic value.
* The exception `message` is the exact `text/plain` body returned to clients. `requested` and `available` are kept on `InsufficientInventoryException` for logs and metrics only; the client-facing message does not disclose stock levels.

---

# Phase 6: Transactional Service Layer & Concurrency Control

## 6.1 InventoryService
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/service/InventoryService.kt`, annotated `@Service` with a class-level `@Transactional(readOnly = true)` default. The two write operations override it with read-write `@Transactional`.

| Method | Behaviour |
|---|---|
| `getInventory(skuId)` | Primary-key lookup; throws `SkuNotFoundException` if absent. |
| `listAllInventory()` | Returns all SKUs via `findAllByOrderBySkuIdAsc()` in byte-wise order. |
| `createOrUpdateInventory(skuId, quantityToAdd)` | Single atomic `INSERT … ON CONFLICT (sku_id) DO UPDATE` upsert. If the upsert writes 0 rows, the addition would overflow a 32-bit INTEGER and `InvalidQuantityException` is thrown with stock unchanged. Re-reads the row it just wrote and returns it. |
| `purchaseInventory(skuId, quantityToDeduct)` | Locks the row with `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), throws `SkuNotFoundException` or `InsufficientInventoryException` as applicable, otherwise deducts and calls `saveAndFlush`. |

Both write methods also reject quantities below 1 with `InvalidQuantityException`, guarding any caller of the service, not only the HTTP layer.

## 6.2 Why Database Row Locks Instead of JVM-Local Synchronization
A purchase is a read-check-write sequence. Without isolation, two concurrent requests can both read the same stock level, both pass the check, and both deduct, which oversells.

JVM-local mechanisms (`synchronized`, `ReentrantLock`, a per-SKU `ConcurrentHashMap` of locks, Kotlin `Mutex`) were rejected because:
* **They only protect one process.** The service runs as multiple replicas behind a load balancer; a lock in one pod's heap does nothing to stop a purchase routed to another pod.
* **They do not cover other writers** such as restock jobs, admin scripts, or data fixes that write to the same table.
* **Lock scope does not match transaction scope.** A JVM lock released before the database commit re-opens the race window.
* **They block request threads** and, under a hot-SKU flash sale, starve the pod of workers for unrelated traffic.

Instead, all correctness comes from PostgreSQL:
* **Purchases** run `SELECT … FOR NO KEY UPDATE` (Hibernate's rendering of `PESSIMISTIC_WRITE`) inside the transaction. Concurrent purchases of the same SKU, from any replica, queue on the row and each one re-reads the latest committed quantity once it acquires the lock. Purchases of different SKUs never block each other.
* **Restocks** use `INSERT … ON CONFLICT DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity`. The addition is computed from the row's current value inside PostgreSQL, so concurrent restocks are never lost, and two racing first-time inserts of the same new SKU cannot fail on the primary key.
* **Lock lifetime equals transaction lifetime.** Locks are released on commit or rollback, or automatically if the connection or pod dies.
* **Bounded waits.** Every pooled connection is opened with `lock_timeout=3s`, `statement_timeout=5s`, and `idle_in_transaction_session_timeout=10s`, so a stuck transaction cannot pin request threads indefinitely.
* **Defence in depth.** The `chk_inventory_quantity_non_negative` CHECK constraint rejects any write of negative stock even if an application path were ever to bypass the service checks.

## 6.3 Verification
Ran the service directly against a throwaway local PostgreSQL with 32 concurrent worker threads:

| Scenario | Result |
|---|---|
| 100 units in stock, 250 parallel purchases of 1 | Exactly 100 succeeded, 150 raised `InsufficientInventoryException`, final stock 0; the remaining quantities returned were exactly 0–99 |
| 50 parallel first-time restocks of a new SKU (+2 each) | All 50 succeeded with no primary-key violations; final stock 100 |
| 200 random restocks (+3) and purchases (−5) on one SKU | `initial + added − purchased = final` held with no unexpected exceptions |
| Restock past `Int.MAX_VALUE` | `InvalidQuantityException`; stock unchanged |

The SQL log confirmed the purchase path issues `select … from inventory … where sku_id=? for no key update`.

---

# Phase 7: Presentation Layer, Validation & Error Mapping

## 7.1 Request and Response DTOs
**Action:** Created DTOs under `src/main/kotlin/com/nuuly/assessment/inventory/api/dto/` named after the OpenAPI schemas.

* `InventoryQuantity(quantity: Int?)` with `@field:NotNull(message = "quantity is required")` and `@field:Min(value = 1, message = "quantity must be greater than or equal to 1")`. The field is nullable on purpose so a missing field and an explicit `null` both reach Bean Validation and return the same message, rather than failing earlier inside Jackson's Kotlin module.
* `InventoryItem(skuId: String, quantity: Int)` with a `from(entity)` factory.

## 7.2 InventoryController
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/api/InventoryController.kt` with constructor injection of `InventoryService` and handlers for all four OpenAPI routes:

| Route | Handler | Success |
|---|---|---|
| `GET /inventory` | `listInventory()` | `200` JSON array (`[]` when empty) |
| `GET /inventory/{skuId}` | `getInventory()` | `200` JSON `InventoryItem` |
| `POST /inventory/{skuId}` | `createInventory()` | `200` JSON `InventoryItem` (create or add) |
| `POST /inventory/{skuId}/purchase` | `purchaseItem()` | `200` JSON `InventoryItem` with remaining stock |

Request bodies use `@Valid`. The `skuId` path variable is constrained with `@NotBlank` and `@Size(max = 64)` to match the `VARCHAR(64)` column. The controller is deliberately not annotated `@Validated`, so Spring's built-in method validation reports path and body violations together as a single `HandlerMethodValidationException`.

## 7.3 Strict Jackson Configuration
Jackson's defaults silently accept malformed quantities, which is unsafe for a stock-movement API. `src/main/resources/application.yml` disables both lenient behaviours:

```yaml
spring:
  jackson:
    deserialization:
      accept-float-as-int: false        # {"quantity": 2.7} is rejected, not truncated to 2
    mapper:
      allow-coercion-of-scalars: false  # {"quantity": "5"} is rejected, not coerced to 5
```

| Input | Jackson default | With strict configuration |
|---|---|---|
| `{"quantity": 2.7}` | Truncated to `2` | `400 Malformed request body…` |
| `{"quantity": "5"}` | Coerced to `5` | `400 Malformed request body…` |
| `{"quantity": true}` | Rejected | `400 Malformed request body…` |
| `{"quantity": 3000000000}` | Rejected (out of 32-bit range) | `400 Malformed request body…` |

## 7.4 GlobalExceptionHandler
**Action:** Created `src/main/kotlin/com/nuuly/assessment/inventory/api/exception/GlobalExceptionHandler.kt`, a `@RestControllerAdvice` that renders every failure as `text/plain;charset=UTF-8`, matching the contract's `Error` schema (`type: string`).

| Trigger | Status | Body |
|---|---|---|
| `SkuNotFoundException` | `404` | `SKU not found: {skuId}` |
| `InsufficientInventoryException` | `400` | `Insufficient inventory` |
| `InvalidQuantityException` | `400` | Exception message |
| `HandlerMethodValidationException` / `MethodArgumentNotValidException` | `400` | First constraint message, `skuId` before body |
| `HttpMessageNotReadableException` (missing body, invalid JSON, non-integer quantity) | `400` | `Malformed request body: quantity must be an integer between 1 and 2147483647` |
| `HttpMediaTypeNotSupportedException` | `415` | `Content-Type must be application/json` |
| `HttpRequestMethodNotSupportedException` | `405` | `Method {method} not allowed` with `Allow` header |
| `NoResourceFoundException` | `404` | `Not found` |
| `PessimisticLockingFailureException` / `QueryTimeoutException` | `503` | `Inventory is busy, please retry` with `Retry-After: 1` |
| `DataIntegrityViolationException` with SQLSTATE `23514` | `400` | `Insufficient inventory` (logged at `ERROR`) |
| Any other exception | `500` | `Internal server error` (details logged, never returned) |

**Key Decisions:**
* Every response sets its Content-Type explicitly, which makes Spring skip content negotiation; a client sending `Accept: application/json` still receives the plain-text error defined by the contract instead of a `406`.
* The class does not extend `ResponseEntityExceptionHandler`, whose built-in handlers emit `application/problem+json`.

## 7.5 Verification
Booted the packaged jar against a throwaway local PostgreSQL and exercised every row of the mapping table with `curl`, including holding the row lock from a separate `psql` session for 5 seconds during a purchase (returned `503` with `Retry-After: 1` after the 3-second `lock_timeout`). All 28 cases returned the expected status, body, and `text/plain;charset=UTF-8` Content-Type.

---

# Phase 8: Integration Test Suite

## 8.1 Testcontainers Base Class
**Action:** Created `src/test/kotlin/com/nuuly/assessment/inventory/IntegrationTestBase.kt`.

* Starts a single `postgres:15-alpine` container per test JVM (the singleton-container pattern), matching the image in `docker-compose.yml`. A per-class `@Container` was avoided because it stops the database between test classes while Spring's cached application context still points at it.
* Registers the container's JDBC URL, username, and password through `@DynamicPropertySource`, so Flyway applies `V1__create_inventory_table.sql` to the container on startup and `ddl-auto: validate` checks the entity mapping against it. No `.env` file is required to run the tests.
* Truncates the `inventory` table before every test so results are independent of execution order.

## 8.2 API and Concurrency Integration Tests
**Action:** Created `src/test/kotlin/com/nuuly/assessment/inventory/api/InventoryApiIntegrationTest.kt`, a `@SpringBootTest(webEnvironment = RANDOM_PORT)` suite that calls the API over real HTTP with `TestRestTemplate` and asserts both the HTTP response and the resulting database row.

**Functional coverage (41 test cases):**
* `GET /inventory`: empty array; byte-wise SKU ordering.
* `POST /inventory/{skuId}`: create, add to existing, overflow rejected with stock unchanged, `415` for non-JSON, 64-character SKU accepted, 65-character SKU rejected.
* `GET /inventory/{skuId}`: found, plain-text `404`, plain-text error even with `Accept: application/json`, case-sensitive SKU IDs.
* `POST /inventory/{skuId}/purchase`: partial purchase, purchase of all remaining stock, insufficient stock with nothing deducted, sold-out SKU, unknown SKU, body validated before SKU lookup.
* Ten invalid bodies run against both POST routes (`{}`, `null`, `0`, `-5`, `2.7`, `"5"`, `true`, `3000000000`, truncated JSON, empty body), each asserting the exact plain-text message and an unchanged database.

**Concurrency gates** (32 client threads released together by a `CountDownLatch`):
* **No overselling:** 100 units, 250 parallel purchases of 1. Asserts exactly 100 × `200` and 150 × `400 Insufficient inventory`, no other status codes, the set of remaining quantities returned is exactly 0–99 (no two purchases observed the same stock level), and final database stock is 0.
* **No lost restocks:** 50 parallel first-time restocks of a new SKU (+2 each). Asserts all succeed and final stock is 100.
* **Mixed-load invariant:** 200 random restocks (+3) and purchases (−5). Asserts only `200` or `400 Insufficient inventory` responses, and `initial + added − sold = final ≥ 0`.

## 8.3 Verification
* **First run (Docker unavailable):** All 41 tests passed against a throwaway local PostgreSQL that temporarily stood in for the Testcontainers instance, by overriding the base class's datasource properties. The committed base class was left unchanged.
* **Real Testcontainers run:** With Docker Desktop running, `./gradlew test` first failed every test with `Status 400` from Docker. The fix is described in Phase 9.1. After it, all tests passed against a real `postgres:15-alpine` container: Testcontainers reported `Server Version: 29.8.2` and the container started in under a second.

---

# Phase 9: Reviewer Readiness Audit

**Action:** Checked the finished repository against every item in the Nuuly assessment brief: the OpenAPI contract, the AI-usage guidance, and the submission requirements. A fresh `git clone` of the published repository was used to reproduce what a reviewer would experience. All functional requirements were met. Five issues affecting a reviewer's first run were found and fixed.

## 9.1 Testcontainers Incompatibility with Docker Engine 29
**Problem:** `./gradlew test` failed all tests on a machine running Docker Desktop with Docker Engine 29.8.2. Testcontainers logged `DockerDesktopClientProviderStrategy: failed with exception BadRequestException (Status 400 …)`.

**Cause:** Spring Boot 3.5.6 manages Testcontainers 1.21.3. Its default Docker API version is below the minimum that Docker Engine 29 accepts, so the engine rejects the handshake. The tests themselves were never reached. Any reviewer on a current Docker Desktop would have hit the same failure.

**Resolution:** Pinned the managed version in `build.gradle.kts`:

```kotlin
extra["testcontainers.version"] = "1.21.4"
```

Testcontainers 1.21.4 negotiates a supported API version. The full suite then passed against a real `postgres:15-alpine` container.

## 9.2 Zero-Edit Local Credentials
**Problem:** Following the README literally on a fresh clone (`cp .env.example .env`, then `docker compose up -d`) failed with `required variable POSTGRES_USER is missing a value`, because the template's credentials were empty. That conflicts with the brief's requirement to start "with minimal setup".

**Resolution:** `.env.example` now ships working local-only values (`POSTGRES_USER=inventory`, `POSTGRES_PASSWORD=inventory_local_dev`), and the README's step 1 is just `cp .env.example .env`.

**Why this does not weaken the secrets policy:**
* These values only protect a throwaway Postgres container bound to the reviewer's own machine.
* The real `.env` file is still git-ignored.
* `application.yml` still has no credential defaults, so deployed environments must supply real values from their secret store.
* The `:?` guards in `docker-compose.yml` still fail fast if a value is removed.

## 9.3 Contract Alignment for `GET /inventory/{skuId}`
**Problem:** A `GET` for a SKU ID longer than 64 characters returned `400`. The contract defines only `200` and `404` for `getInventory`.

**Resolution:** Removed the `@NotBlank` / `@Size` constraints from the `GET` path variable only. A blank or over-long SKU cannot exist in the table, so the lookup now returns `404 SKU not found: …` naturally. The `POST` routes keep the constraints, because the contract allows `400` there and a 65-character SKU must never be created. Added the integration test `get returns 404 rather than 400 for a SKU id too long to exist`, and updated `tech-spec.md` §5.1, §5.4 row 5, and §5.5.

## 9.4 README Troubleshooting
**Problem:** Several environment problems met during development were undocumented, and a reviewer could hit any of them:
* A Homebrew PostgreSQL on `127.0.0.1:5432` intercepted the app's connection, causing `FATAL: role "nuuly_user" does not exist`.
* The `docker` CLI was missing from `PATH` while Docker Desktop was running.
* Gradle failed on Java 27.
* `bootRun` appeared stuck at `85% EXECUTING`.

**Resolution:** Added a Troubleshooting section to `README.md` covering each case with the diagnostic command and the fix. It also covers a `pgdata` volume created earlier with different credentials, which Postgres ignores after first initialization. Also corrected the Swagger Editor link to `https://editor.swagger.io`.

## 9.5 Repository Hygiene
* Removed `nuuly-inventory-api-commands.txt`, which only contained the output of `git init`.
* Exported the AI-assisted development session transcript for review before deciding whether to add it to `.ai-artifacts/`, per the brief's request for chat exports.

## 9.6 Verification
`./gradlew test` with Docker running: 42 tests passed against `postgres:15-alpine` (the 41 from Phase 8 plus the new `GET` contract test).
