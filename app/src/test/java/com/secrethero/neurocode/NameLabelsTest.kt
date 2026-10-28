package com.secrethero.neurocode

import com.secrethero.neurocode.ui.NameLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameLabelsTest {
    @Test
    fun shortIdIsStableAndIgnoresDashes() {
        val id = "79c05b22-c46d-4cf0-812f-24d3963cb9e4"

        assertEquals("79c0", NameLabels.shortId(id))
        assertEquals(NameLabels.shortId(id), NameLabels.shortId(id))
    }

    @Test
    fun duplicatesIgnoreCaseAndWhitespace() {
        val duplicated = NameLabels.duplicated(listOf("Demo", "  demo ", "Другой", "A  b", "a b"))

        assertEquals(setOf("demo", "a b"), duplicated)
    }

    @Test
    fun uniqueNamesGetNoSuffix() {
        val duplicated = NameLabels.duplicated(listOf("Alpha", "Beta"))

        assertEquals("Alpha", NameLabels.label("Alpha", "11112222", duplicated))
    }

    @Test
    fun duplicateNamesGetShortIdSoTheyDiffer() {
        val duplicated = NameLabels.duplicated(listOf("Demo", "demo"))

        val first = NameLabels.label("Demo", "aaaa-1111", duplicated)
        val second = NameLabels.label("demo", "bbbb-2222", duplicated)

        assertEquals("Demo · aaaa", first)
        assertEquals("demo · bbbb", second)
    }

    @Test
    fun collisionDetectsExistingNameOnly() {
        val existing = listOf("Demo", "Сайт")

        assertTrue(NameLabels.collides(" demo ", existing))
        assertTrue(NameLabels.collides("САЙТ", existing))
        assertFalse(NameLabels.collides("Demo 2", existing))
        assertFalse(NameLabels.collides("   ", existing))
    }
}
