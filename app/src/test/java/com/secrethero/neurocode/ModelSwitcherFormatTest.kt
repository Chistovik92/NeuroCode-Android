package com.secrethero.neurocode

import com.secrethero.neurocode.ui.screens.breakableModelId
import com.secrethero.neurocode.ui.screens.isFreeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSwitcherFormatTest {
    private val zeroWidth = 0x200B.toChar()

    @Test
    fun breakableModelIdAddsBreakPointsAfterSlashAndColon() {
        val result = breakableModelId("org/model:tag")

        assertEquals("org/${zeroWidth}model:${zeroWidth}tag", result)
        assertEquals("org/model:tag", result.replace(zeroWidth.toString(), ""))
    }

    @Test
    fun breakableModelIdKeepsPlainNameUntouched() {
        assertEquals("gpt-4o", breakableModelId("gpt-4o"))
    }

    @Test
    fun freeModelsAreDetectedBySuffix() {
        assertTrue(isFreeModel("meta/llama-3:free"))
        assertTrue(isFreeModel("meta/llama-3:FREE"))
        assertFalse(isFreeModel("meta/llama-3:free-extended"))
        assertFalse(isFreeModel("freemodel"))
    }
}
