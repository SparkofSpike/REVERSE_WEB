package com.test.engine.exception;

/**
 * Business rule violation carried to the API layer as a 400 response.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
