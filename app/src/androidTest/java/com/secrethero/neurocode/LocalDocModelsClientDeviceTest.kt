package com.secrethero.neurocode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secrethero.neurocode.ai.LocalDocModelsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Межпроцессный контракт с установленным LocalDoc AI release/debug. */
@RunWith(AndroidJUnit4::class)
class LocalDocModelsClientDeviceTest {
    @Test
    fun debugProviderIsListedAndOpened() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = LocalDocModelsClient(context)
        val model = client.list().firstOrNull()
        assertTrue("LocalDoc AI provider did not return a downloaded model", model != null)
        client.open(model!!)!!.use {
            assertTrue(it.readNBytes(4).contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46)))
        }
    }
}
