package com.ratelimit.policy.admin;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.Arrays;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns failures into the admin API's error responses. A failed change never leaves anything saved. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** The request describes an invalid rule or key. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String message = "The request body is missing or is not valid JSON.";
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            message = "Unknown field '" + unknown.getPropertyName() + "'.";
        } else if (e.getCause() instanceof InvalidFormatException format && !format.getPath().isEmpty()) {
            message = "Invalid value '" + format.getValue() + "' for field '" + format.getPath().get(0).getFieldName() + "'.";
            if (format.getTargetType().isEnum()) {
                message += " Allowed: " + Arrays.toString(format.getTargetType().getEnumConstants()) + ".";
            }
        }
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badPathValue(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid value for '" + e.getName() + "'.");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException e) {
        return respond(HttpStatus.CONFLICT, "conflict",
                "An active rule already exists for this scope (limit_by, tier, subject, path pattern and method).");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> constraint(DataIntegrityViolationException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", "The database refused the change.");
    }

    /** A rule already in the database is invalid (for example edited by hand); the message names it. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> storedRuleInvalid(IllegalStateException e) {
        return respond(HttpStatus.CONFLICT, "stored_rule_invalid", e.getMessage() + ". Fix or deactivate that rule first.");
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ApiError(error, message));
    }
}
