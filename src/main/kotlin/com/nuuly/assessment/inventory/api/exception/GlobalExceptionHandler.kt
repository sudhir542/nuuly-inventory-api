package com.nuuly.assessment.inventory.api.exception

import com.nuuly.assessment.inventory.domain.exception.InsufficientInventoryException
import com.nuuly.assessment.inventory.domain.exception.InvalidQuantityException
import com.nuuly.assessment.inventory.domain.exception.SkuNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.nio.charset.StandardCharsets
import java.sql.SQLException

/**
 * Translates every failure into the contract's `Error` schema: a single human-readable string
 * served as `text/plain` (tech-spec.md §5.4).
 *
 * Each response sets its Content-Type explicitly. Spring then skips content negotiation, so a
 * client sending `Accept: application/json` still gets the plain-text error the contract
 * defines instead of a 406. This class intentionally does not extend
 * `ResponseEntityExceptionHandler`, whose built-in handlers emit `application/problem+json`.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- Domain exceptions -------------------------------------------------------------------

    @ExceptionHandler(SkuNotFoundException::class)
    fun handleSkuNotFound(ex: SkuNotFoundException): ResponseEntity<String> {
        log.debug("SKU not found: {}", ex.skuId)
        return plainText(HttpStatus.NOT_FOUND, ex.message!!)
    }

    @ExceptionHandler(InsufficientInventoryException::class)
    fun handleInsufficientInventory(ex: InsufficientInventoryException): ResponseEntity<String> {
        log.debug("Insufficient inventory for SKU {}: requested={}, available={}", ex.skuId, ex.requested, ex.available)
        return plainText(HttpStatus.BAD_REQUEST, ex.message!!)
    }

    @ExceptionHandler(InvalidQuantityException::class)
    fun handleInvalidQuantity(ex: InvalidQuantityException): ResponseEntity<String> {
        log.debug("Invalid quantity: {}", ex.message)
        return plainText(HttpStatus.BAD_REQUEST, ex.message!!)
    }

    // --- Request validation and deserialization ----------------------------------------------

    /**
     * Raised by Spring's built-in method validation, which applies because the controller's
     * path variables carry constraints. Covers both `skuId` and the `@Valid` request body.
     * Results are reported in parameter order (skuId, then body) so the message is deterministic.
     */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidation(ex: HandlerMethodValidationException): ResponseEntity<String> {
        val message = ex.parameterValidationResults
            .sortedBy { it.methodParameter.parameterIndex }
            .flatMap { it.resolvableErrors }
            .firstNotNullOfOrNull { it.defaultMessage }
            ?: INVALID_REQUEST
        log.debug("Request validation failed: {}", message)
        return plainText(HttpStatus.BAD_REQUEST, message)
    }

    /** Raised when a `@Valid` body fails validation on a handler that has no other constraints. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<String> {
        val message = ex.bindingResult.allErrors.firstNotNullOfOrNull { it.defaultMessage } ?: INVALID_REQUEST
        log.debug("Request body validation failed: {}", message)
        return plainText(HttpStatus.BAD_REQUEST, message)
    }

    /**
     * Missing body, invalid JSON, or a quantity that is not a whole number in 32-bit range
     * (floats and numeric strings are rejected by the strict Jackson settings in application.yml).
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<String> {
        log.debug("Unreadable request body: {}", ex.mostSpecificCause.message)
        return plainText(HttpStatus.BAD_REQUEST, MALFORMED_BODY)
    }

    // --- HTTP protocol errors ----------------------------------------------------------------

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(ex: HttpMediaTypeNotSupportedException): ResponseEntity<String> =
        plainText(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<String> =
        plainText(HttpStatus.METHOD_NOT_ALLOWED, "Method ${ex.method} not allowed", ex.headers)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException): ResponseEntity<String> =
        plainText(HttpStatus.NOT_FOUND, "Not found")

    // --- Database --------------------------------------------------------------------------

    /** Row-lock wait exceeded `lock_timeout`, a deadlock was detected, or a statement timed out. */
    @ExceptionHandler(PessimisticLockingFailureException::class, QueryTimeoutException::class)
    fun handleLockContention(ex: RuntimeException): ResponseEntity<String> {
        log.warn("Database contention, request rejected for retry: {}", ex.mostSpecificMessage())
        val headers = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, "1") }
        return plainText(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is busy, please retry", headers)
    }

    /**
     * The non-negative CHECK constraint rejected a write. Application checks run first, so
     * reaching this means a code path bypassed them and needs investigation.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<String> {
        if (ex.sqlState() == CHECK_VIOLATION_SQL_STATE) {
            log.error("CHECK constraint violation reached the database", ex)
            return plainText(HttpStatus.BAD_REQUEST, "Insufficient inventory")
        }
        log.error("Unexpected data integrity violation", ex)
        return plainText(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR)
    }

    // --- Fallback --------------------------------------------------------------------------

    /**
     * Any other Spring MVC exception that carries its own HTTP status (e.g. 406 Not Acceptable)
     * keeps that status; everything else is an unexpected 500 whose details are logged, never
     * returned.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<String> {
        if (ex is ErrorResponse) {
            val status = ex.statusCode
            log.debug("Request rejected with {}: {}", status, ex.message)
            return plainText(status, reasonPhrase(status), ex.headers)
        }
        log.error("Unhandled exception", ex)
        return plainText(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR)
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun plainText(
        status: HttpStatusCode,
        body: String,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): ResponseEntity<String> =
        ResponseEntity.status(status)
            .headers(headers)
            .contentType(TEXT_PLAIN_UTF8)
            .body(body)

    private fun reasonPhrase(status: HttpStatusCode): String =
        HttpStatus.resolve(status.value())?.reasonPhrase ?: "Request failed"

    private fun DataIntegrityViolationException.sqlState(): String? =
        generateSequence(this as Throwable) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
            ?.sqlState

    private fun RuntimeException.mostSpecificMessage(): String? =
        generateSequence(this as Throwable) { it.cause }.last().message

    private companion object {
        val TEXT_PLAIN_UTF8 = MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)
        const val CHECK_VIOLATION_SQL_STATE = "23514"
        const val INVALID_REQUEST = "Invalid request"
        const val MALFORMED_BODY = "Malformed request body: quantity must be an integer between 1 and 2147483647"
        const val INTERNAL_ERROR = "Internal server error"
    }
}
