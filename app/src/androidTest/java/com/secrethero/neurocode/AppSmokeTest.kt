package com.secrethero.neurocode

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @Test
    fun appStartsAndSettingsTabOpens() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withText("Настройки")).perform(click())
        onView(withText("Режим работы")).check(matches(isDisplayed()))
        onView(withText("API-провайдеры")).check(matches(isDisplayed()))
    }
}
