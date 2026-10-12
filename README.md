# Nuuly Inventory API

A Kotlin / Spring Boot REST service that tracks stock on hand per SKU, backed by PostgreSQL. Concurrent purchases are made safe with database row locks, so the service stays correct across any number of replicas.

* Design: [`tech-spec.md`](tech-spec.md)
* Build log: [`setup.md`](setup.md)

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17–26 to run Gradle | Gradle 9.6 cannot run on Java 27. The JDK 17 toolchain used to compile is downloaded automatically. |
| Docker | Docker Desktop or any Docker Engine | Needed for the local database and for the Testcontainers test suite. |
| curl | any | For calling the endpoints. |

If your default `java` is 27 or newer, point Gradle at an older JDK for the current shell:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

## 1. Configure database credentials

Credentials live in a git-ignored `.env` file, which both Docker Compose and the application read.

```bash
cp .env.example .env
```

Edit `.env` and set `POSTGRES_USER` and `POSTGRES_PASSWORD` to any values you choose.

## 2. Start PostgreSQL

```bash
docker compose up -d
```

Confirm the container `nuuly-inventory-db` is running on port 5432:

```bash
docker ps
```

## 3. Build and test

Run the full test suite. It starts its own throwaway `postgres:15-alpine` container through Testcontainers, so it does not touch the database from step 2, but Docker must be running.

```bash
./gradlew test
```

The HTML report is written to `build/reports/tests/test/index.html`.

Compile, test, and package the executable jar in one step:

```bash
./gradlew build
```

## 4. Run the application

```bash
./gradlew bootRun
```

The API listens on `http://localhost:8080`. Flyway creates the `inventory` table on first start. Check readiness:

```bash
curl -s http://localhost:8080/actuator/health/readiness
```

Expected: `{"status":"UP"}`

## 5. Call the endpoints

Add stock for a new SKU (creates it):

```bash
curl -s -X POST http://localhost:8080/inventory/CW-XYCS-BM-01 -H 'Content-Type: application/json' -d '{"quantity": 10}'
```

Expected: `{"skuId":"CW-XYCS-BM-01","quantity":10}`

Add more stock to the same SKU:

```bash
curl -s -X POST http://localhost:8080/inventory/CW-XYCS-BM-01 -H 'Content-Type: application/json' -d '{"quantity": 5}'
```

Expected: `{"skuId":"CW-XYCS-BM-01","quantity":15}`

Read one SKU:

```bash
curl -s http://localhost:8080/inventory/CW-XYCS-BM-01
```

Expected: `{"skuId":"CW-XYCS-BM-01","quantity":15}`

List all SKUs:

```bash
curl -s http://localhost:8080/inventory
```

Expected: `[{"skuId":"CW-XYCS-BM-01","quantity":15}]`

Purchase units:

```bash
curl -s -X POST http://localhost:8080/inventory/CW-XYCS-BM-01/purchase -H 'Content-Type: application/json' -d '{"quantity": 4}'
```

Expected: `{"skuId":"CW-XYCS-BM-01","quantity":11}`

### Error responses

Errors are returned as `text/plain`. Use `-i` to see the status line.

Purchase more than is in stock (`400`):

```bash
curl -si -X POST http://localhost:8080/inventory/CW-XYCS-BM-01/purchase -H 'Content-Type: application/json' -d '{"quantity": 100}'
```

Body: `Insufficient inventory`

Unknown SKU (`404`):

```bash
curl -si http://localhost:8080/inventory/does-not-exist
```

Body: `SKU not found: does-not-exist`

Invalid quantity (`400`):

```bash
curl -si -X POST http://localhost:8080/inventory/CW-XYCS-BM-01 -H 'Content-Type: application/json' -d '{"quantity": -3}'
```

Body: `quantity must be greater than or equal to 1`

Non-integer quantity (`400`):

```bash
curl -si -X POST http://localhost:8080/inventory/CW-XYCS-BM-01 -H 'Content-Type: application/json' -d '{"quantity": 2.7}'
```

Body: `Malformed request body: quantity must be an integer between 1 and 2147483647`

| Status | When |
|---|---|
| `400` | Invalid or missing quantity, malformed JSON, SKU ID longer than 64 characters, insufficient stock, or a restock past 2,147,483,647 |
| `404` | SKU does not exist, or unknown path |
| `405` | HTTP method not supported on the path |
| `415` | Request body is not `application/json` |
| `503` | The SKU's row lock could not be acquired within 3 seconds; retry after the `Retry-After` interval |

## 6. Stop

Stop the application with `Ctrl+C`, then stop the database:

```bash
docker compose down
```

To also delete all stored inventory data, remove the volume:

```bash
docker compose down -v
```
