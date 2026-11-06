package com.secrethero.neurocode

import com.secrethero.neurocode.ai.HuggingFaceFile
import com.secrethero.neurocode.ai.HuggingFaceQuantization
import com.secrethero.neurocode.ai.ModelCatalog
import com.secrethero.neurocode.ai.ModelFamily
import com.secrethero.neurocode.device.DeviceAbi
import com.secrethero.neurocode.device.DeviceProfile
import com.secrethero.neurocode.device.ModelRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private fun profile(maxMb: Long) = DeviceProfile.recommendModel(
        totalMemoryMb = maxMb * 2,
        cores = 8,
        abi = DeviceAbi.ARM64,
        reportedLowRam = false,
    )

    @Test
    fun catalogHasExpectedFamilies() {
        val families = ModelCatalog.starter.map { it.family }.toSet()
        assertTrue(ModelFamily.QWEN in families)
        assertTrue(ModelFamily.GEMMA in families)
        assertTrue(ModelFamily.LLAMA in families)
        assertTrue(ModelFamily.DEEPSEEK in families)
        assertTrue(ModelFamily.VIKHR in families)
    }

    @Test
    fun catalogIdsAreUniqueAndStable() {
        val ids = ModelCatalog.starter.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun recommendedModelsFitTheDevice() {
        val fitting = ModelRecommender.recommendedFor(ModelCatalog.starter, profile(4_000))
        assertTrue(fitting.isNotEmpty())
        assertTrue(fitting.all { it.sizeMb <= 4_000 })
        // Рекомендованные идут первыми среди помещающихся.
        assertEquals(fitting.first().id, ModelRecommender.rank(ModelCatalog.starter, profile(4_000))
            .filter { !it.tooLarge }.first().spec.id)
    }

    @Test
    fun tinyDeviceGetsOnlySmallModels() {
        val fitting = ModelRecommender.fitting(ModelCatalog.starter, profile(1_500))
        assertTrue(fitting.isNotEmpty())
        assertTrue(fitting.all { it.sizeMb <= 1_500 })
    }

    @Test
    fun tooLargeIsFlaggedNotHidden() {
        val p = profile(1_000)
        val ranked = ModelRecommender.rank(ModelCatalog.starter, p)
        // Модели, не помещающиеся в память, не скрываются, а помечаются флагом tooLarge.
        assertTrue(ranked.any { it.tooLarge })
        assertEquals(
            ranked.filter { it.spec.sizeMb > p.maxModelSizeMb }.map { it.spec.id }.toSet(),
            ranked.filter { it.tooLarge }.map { it.spec.id }.toSet(),
        )
    }

    @Test
    fun pickFilePrefersQ4KM() {
        val files = listOf(
            HuggingFaceFile("m-q4_0.gguf", 800L * 1024 * 1024, null),
            HuggingFaceFile("m-q4_k_m.gguf", 900L * 1024 * 1024, "sha1"),
            HuggingFaceFile("m-q8_0.gguf", 1_400L * 1024 * 1024, "sha2"),
        )
        val picked = HuggingFaceQuantization.pick(files, 2_000)
        assertNotNull(picked)
        assertTrue(picked!!.path.contains("q4_k_m", ignoreCase = true))
    }

    @Test
    fun pickFileSkipsTooLarge() {
        val files = listOf(
            HuggingFaceFile("m-q4_k_m.gguf", 3_000L * 1024 * 1024, null),
            HuggingFaceFile("m-q4_0.gguf", 800L * 1024 * 1024, null),
        )
        val picked = HuggingFaceQuantization.pick(files, 1_000)
        assertNotNull(picked)
        assertTrue(picked!!.path.contains("q4_0", ignoreCase = true))
    }

    @Test
    fun pickFileReturnsNullWhenNothingFits() {
        val files = listOf(HuggingFaceFile("big-q8_0.gguf", 5_000L * 1024 * 1024, null))
        assertTrue(HuggingFaceQuantization.pick(files, 1_000) == null)
    }
}
