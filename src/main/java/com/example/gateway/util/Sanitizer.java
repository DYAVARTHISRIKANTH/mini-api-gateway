package com.example.gateway.util;

/**
 * Utility class for input sanitization to prevent Log Injection (CRLF injection)
 * and ensure data integrity of incoming headers and metadata.
 */
public final class Sanitizer {

    private Sanitizer() {
        // Private constructor to prevent instantiation
    }

    /**
     * Sanitizes general string input by replacing carriage returns and line feeds
     * to protect against CRLF injection in logs.
     *
     * @param input the raw string
     * @return the sanitized string or null if input was null
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("\n", "_").replace("\r", "_");
    }

    /**
     * Sanitizes a client IP address, ensuring it only contains valid IPv4/IPv6 characters
     * and preventing CRLF injection.
     *
     * @param ip the raw client IP address
     * @return a sanitized IP address string or "0.0.0.0" if invalid
     */
    public static String sanitizeIp(String ip) {
        if (ip == null) {
            return "0.0.0.0";
        }
        String cleanIp = ip.trim().replace("\n", "").replace("\r", "");
        
        // Validate characters to match standard IPv4 (e.g. 192.168.1.1) and IPv6 (e.g. 2001:db8::1)
        if (cleanIp.matches("^[a-zA-Z0-9.:%\\-]+$")) {
            return cleanIp;
        }
        return "0.0.0.0";
    }
}
