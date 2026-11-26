package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.host.Feedback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.1: feedback leaves the phone (a GitHub issue is public), so the report is redacted once more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V201FeedbackTest {
    private val report = """
        Agent-VScreen 2.0.1-beta1 (75), service protocol 18
        device: Xiaomi POCO F5, sdk 34, os HyperOS
        connection: READY, tailnet 100.101.102.103 phone.tail1234.ts.net, lan 192.168.1.23
        public: https://phone.example.org/mcp via cloudflared, ngrok https://name-abc.ngrok-free.app
        contact me@example.com
        token aZ3kQ9vB7xY2mN4pL8rT6wS1cD5fG0hJ
        apk sha256 006a1c9463c30f565c3043431d60b3c517c489036aff0236e68fb166497af9db
        local http://127.0.0.1:18765/v2/ping, docs https://github.com/kilolonion/agent-vscreen/issues
        display flags: PUBLIC|TRUSTED|OWN_DISPLAY_GROUP, code NOT_READ at 12:03:04

        app log
        com.kilon.agentvscreen.core.VScreenAwake keeper start
    """.trimIndent()

    @Test fun redactsWhoAndWhereButKeepsWhatHelps() {
        val r = Feedback.redact(report, listOf("phone.example.org"))
        listOf("100.101.102.103", "192.168.1.23", "tail1234.ts.net", "phone.example.org", "ngrok-free.app", "me@example.com", "aZ3kQ9vB7xY2mN4pL8rT6wS1cD5fG0hJ")
            .forEach { assertFalse("leaked $it", r.contains(it)) }
        listOf("2.0.1-beta1", "POCO F5", "sdk 34", "127.0.0.1:18765", "https://github.com/kilolonion/agent-vscreen/issues",
            "006a1c9463c30f565c3043431d60b3c517c489036aff0236e68fb166497af9db", "OWN_DISPLAY_GROUP", "NOT_READ", "12:03:04",
            "com.kilon.agentvscreen.core.VScreenAwake")
            .forEach { assertTrue("lost $it", r.contains(it)) }
    }

    @Test fun issueLinkCarriesOnlyTheSummary() {
        val r = Feedback.redact(report)
        val url = Feedback.issueUrl("[2.0.1-beta1] ", "intro", r)
        assertTrue(url.startsWith(Feedback.ISSUES_NEW + "?"))
        assertFalse(Feedback.summary(r).contains("app log"))
        assertTrue(url.length < 8_000)
    }
}
