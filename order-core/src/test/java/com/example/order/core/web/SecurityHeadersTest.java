package com.example.order.core.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersTest {

    @Test
    void cspForbidsInlineScriptAndStyle() {
        String csp = SecurityHeaders.CONTENT_SECURITY_POLICY;

        assertTrue(csp.contains("script-src 'self'"));
        assertTrue(csp.contains("style-src 'self'"));
        // The UI rule in AGENTS.md depends on this: no 'unsafe-inline' either way.
        assertFalse(csp.contains("unsafe-inline"));
        assertFalse(csp.contains("unsafe-eval"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("base-uri 'none'"));
        assertTrue(csp.contains("form-action 'self'"));
    }

    @Test
    void responseHeadersCoverTheThreeBaselineHeaders() {
        Map<String, String> headers = SecurityHeaders.responseHeaders();

        assertEquals(SecurityHeaders.CONTENT_SECURITY_POLICY, headers.get("Content-Security-Policy"));
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertEquals("no-referrer", headers.get("Referrer-Policy"));
        assertEquals(3, headers.size());
    }
}
