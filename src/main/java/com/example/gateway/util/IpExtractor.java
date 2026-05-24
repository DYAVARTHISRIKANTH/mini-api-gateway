package com.example.gateway.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility class to securely extract the client's original IP address.
 * Inspects proxy headers (like X-Forwarded-For, X-Real-IP) and falls back
 * to HttpServletRequest.getRemoteAddr() under proper sanitization.
 */
public final class IpExtractor {

    private static final String[] IP_HEADER_CANDIDATES = {
        "X-Forwarded-For",
        "X-Real-IP",
        "Proxy-Client-IP",
        "WL-Proxy-Client-IP",
        "HTTP_CLIENT_IP",
        "HTTP_X_FORWARDED_FOR"
    };

    private IpExtractor() {
        // Private constructor to prevent instantiation
    }

    /**
     * Extracts the client IP address from the request context, parsing proxy headers if present.
     *
     * @param request the HttpServletRequest
     * @return the client IP address string
     */
    public static String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADER_CANDIDATES) {
            String ipList = request.getHeader(header);
            if (ipList != null && !ipList.isEmpty() && !"unknown".equalsIgnoreCase(ipList)) {
                // X-Forwarded-For can contain a comma-separated list of proxy IPs.
                // The first element (leftmost) is the client IP.
                String clientIp = ipList.split(",")[0].trim();
                return Sanitizer.sanitizeIp(clientIp);
            }
        }
        return Sanitizer.sanitizeIp(request.getRemoteAddr());
    }
}
