package com.kilon.agentvscreen.cmd

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenTransitionTest {
    @Test fun ordinaryForegroundSwitchIsInformational() {
        assertEquals("MAIN_APP_SWITCHED", mainScreenChangeCode("com.teslacoilsw.launcher", "org.telegram.messenger", emptySet()))
    }
    @Test fun verifiedTaskMigrationIsInformational() {
        assertEquals("MAIN_TASK_MOVED_TO_VSCREEN",
            mainScreenChangeCode("com.openai.chatgpt", "com.openai.chatgpt", setOf(56022), setOf(56022)))
    }
    @Test fun disappearanceOfTasksRequiresUser() {
        assertEquals("MAIN_SCREEN_CHANGED",
            mainScreenChangeCode("com.teslacoilsw.launcher", "org.telegram.messenger", setOf(42)))
        assertEquals("MAIN_SCREEN_CHANGED",
            mainScreenChangeCode("com.openai.chatgpt", "com.openai.chatgpt", setOf(42, 43), setOf(42)))
    }
    @Test fun unchangedOrUnknownScreenCausesNoWarning() {
        assertEquals(null, mainScreenChangeCode("org.telegram.messenger", "org.telegram.messenger", emptySet()))
        assertEquals(null, mainScreenChangeCode(null, "org.telegram.messenger", emptySet()))
    }
}
