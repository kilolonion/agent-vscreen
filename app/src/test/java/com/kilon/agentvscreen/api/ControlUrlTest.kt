package com.kilon.agentvscreen.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.1.8: a control server typed the way people type it must reach the node as a full URL. */
class ControlUrlTest {
    @Test fun normalizes() {
        assertEquals("https://hs.example.com", ApiConfig.normalizeControlUrl(" hs.example.com/ "))
        assertEquals("https://hs.example.com:8443", ApiConfig.normalizeControlUrl("hs.example.com:8443"))
        assertEquals("http://192.168.1.2:8080", ApiConfig.normalizeControlUrl("http://192.168.1.2:8080/"))
        assertNull(ApiConfig.normalizeControlUrl(""))
        assertNull(ApiConfig.normalizeControlUrl("   "))
        assertNull(ApiConfig.normalizeControlUrl("https://controlplane.tailscale.com"))
    }

    @Test fun rejectsNonsense() {
        assertNull(ApiConfig.controlUrlProblem("hs.example.com"))
        assertNull(ApiConfig.controlUrlProblem(""))
        assertNotNull(ApiConfig.controlUrlProblem("ftp://hs.example.com"))
        assertNotNull(ApiConfig.controlUrlProblem("hs example.com"))
    }
}
