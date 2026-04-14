package com.acme.expenses.service;

public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
