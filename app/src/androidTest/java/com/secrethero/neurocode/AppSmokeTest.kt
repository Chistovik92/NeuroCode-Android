package com.secrethero.neurocode

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsAndSettingsTabOpens() {
        val settingsTab = composeRule.activity.getString(R.string.tab_settings)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(settingsTab).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(settingsTab).performClick()
        val workMode = composeRule.activity.getString(R.string.work_mode)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(workMode).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.providers))
            .assertExists()
    }
}
