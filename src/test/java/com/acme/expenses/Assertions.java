package com.acme.expenses;

import java.math.BigDecimal;
import java.util.Objects;

final class Assertions {
    private Assertions() {
    }

    static void equals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void moneyEquals(String expected, BigDecimal actual, String message) {
        BigDecimal expectedAmount = new BigDecimal(expected);
        if (expectedAmount.compareTo(actual) != 0) {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void contains(String actual, String expectedPart, String message) {
        if (actual == null || !actual.contains(expectedPart)) {
            throw new AssertionError(message + " expected to find <" + expectedPart + "> in <" + actual + ">");
        }
    }

    static <T extends Throwable> T throwsType(Class<T> expected, ThrowingRunnable runnable, String message) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return expected.cast(throwable);
            }
            throw new AssertionError(message + " expected " + expected.getSimpleName()
                    + " but got " + throwable.getClass().getSimpleName(), throwable);
        }
        throw new AssertionError(message + " expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
