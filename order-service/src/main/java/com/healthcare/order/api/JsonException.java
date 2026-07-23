package com.healthcare.order.api;

/**
 * Thrown by {@link Json} when a JSON document cannot be parsed (malformed syntax, an
 * unexpected token, or trailing content after a complete value).
 *
 * <p>It is an unchecked exception so parsing helpers read cleanly; the HTTP layer catches it
 * at the request boundary and maps it to a {@code 400 VALIDATION} error envelope.</p>
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a JSON parse exception with a descriptive message.
     *
     * @param message a human-readable description of the parse failure
     */
    public JsonException(String message) {
        super(message);
    }
}
