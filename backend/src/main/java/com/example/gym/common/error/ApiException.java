package com.example.gym.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base application exception carrying an HTTP status and a stable, machine-readable error code.
 * Handled centrally by {@link GlobalExceptionHandler} and rendered as an RFC 9457 problem detail.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
