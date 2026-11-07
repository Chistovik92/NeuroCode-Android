package com.secrethero.neurocode

import com.secrethero.neurocode.data.CredentialKey
import com.secrethero.neurocode.data.SettingsCatalog
import com.secrethero.neurocode.model.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun catalogHasTypedDefaultsAndSearchFindsRussianAndKeys() {
        val settings = AppSettings(maxAgentSteps = 12)
        val defaults = SettingsCatalog.entries(settings)

        assertEquals("12", defaults.single { it.key == "agent.max_steps" }.value)
        assertEquals(1, SettingsCatalog.search(settings, "шагов").size)
        assertEquals(1, SettingsCatalog.search(settings, "guard").size)
        assertTrue(SettingsCatalog.search(settings, "   ").size > 5)
    }

    @Test
    fun credentialNamesContainNoSecretValues() {
        val key = CredentialKey.Provider("deepseek")
        assertEquals("deepseek", key.storageName)

        val serialized = Json.encodeToString(AppSettings.serializer(), AppSettings())
        assertFalse(serialized.contains("apiKey"))
        assertFalse(serialized.contains("token"))
    }
}
