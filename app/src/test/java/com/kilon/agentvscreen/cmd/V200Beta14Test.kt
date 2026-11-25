package com.kilon.agentvscreen.cmd

import org.robolectric.RuntimeEnvironment
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.0-beta14: agent names are kept whole (rainbow: "agent 自己命的名被截断了"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V200Beta14Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ApiConfig.init(ctx)
        Tokens.init(ctx)
    }

    @Test
    fun longNamesAreKept() {
        val pub = Tokens.createPublic("Doubao Agent (cloud browser)", "operate")
        assertEquals("公网·Doubao Agent (cloud browser)", pub.name)
        val (local, _) = Tokens.createLevel("Claude Code on the work laptop", Tokens.FREE)
        assertEquals("Claude Code on the work laptop", local.name)
        Tokens.rename(local.id, "a".repeat(100))
        assertEquals(Tokens.NAME_MAX, Tokens.byId(local.id)!!.name.length)
    }

    @Test
    fun namesCutByOldVersionsComeBack() {
        val full = "Doubao Agent (cloud browser)"
        val pub = Tokens.createPublic(full, "operate")
        Tokens.rename(pub.id, "公网·" + full.take(16))
        Tokens.restorePublicName(pub.id, full)
        assertEquals("公网·$full", Tokens.byId(pub.id)!!.name)
        // A name the user changed is left alone.
        Tokens.rename(pub.id, "My phone helper")
        Tokens.restorePublicName(pub.id, full)
        assertEquals("My phone helper", Tokens.byId(pub.id)!!.name)
    }
}
