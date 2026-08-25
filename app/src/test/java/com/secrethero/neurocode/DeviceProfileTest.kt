package com.secrethero.neurocode

import com.secrethero.neurocode.device.DeviceAbi
import com.secrethero.neurocode.device.DeviceProfile
import com.secrethero.neurocode.device.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun detectsAbiFromSupportedList() {
        assertEquals(
            DeviceAbi.ARM64,
            DeviceAbi.fromSupportedAbis(listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            DeviceAbi.ARMV7,
            DeviceAbi.fromSupportedAbis(listOf("armeabi-v7a", "armeabi")),
        )
        assertEquals(
            DeviceAbi.X86_64,
            DeviceAbi.fromSupportedAbis(listOf("x86_64", "x86")),
        )
        assertEquals(DeviceAbi.UNKNOWN, DeviceAbi.fromSupportedAbis(emptyList()))
        assertEquals(DeviceAbi.ARMV7, DeviceAbi.fromAbiName("armeabi-v7a"))
    }

    @Test
    fun lowRamFlagCoversSmallTotalMemory() {
        assertTrue(DeviceProfile.isLowRam(totalMemoryMb = 2_000, reportedLowRam = false))
        assertTrue(DeviceProfile.isLowRam(totalMemoryMb = 8_192, reportedLowRam = true))
        assertFalse(DeviceProfile.isLowRam(totalMemoryMb = 6_144, reportedLowRam = false))
    }

    @Test
    fun recommendationShrinksFor32BitDevices() {
        val arm64 = DeviceProfile.recommendModel(6_144, cores = 8, abi = DeviceAbi.ARM64, reportedLowRam = false)
        val armv7 = DeviceProfile.recommendModel(6_144, cores = 8, abi = DeviceAbi.ARMV7, reportedLowRam = false)
        assertTrue(armv7.maxModelSizeMb < arm64.maxModelSizeMb)
        assertEquals(DeviceProfile.THIRTY_TWO_BIT_CEILING_MB, armv7.maxModelSizeMb)
        assertTrue(armv7.limitedDevice)
        assertFalse(arm64.limitedDevice)
    }

    @Test
    fun recommendationShrinksForLowRamDevices() {
        val normal = DeviceProfile.recommendModel(3_072, cores = 8, abi = DeviceAbi.ARM64, reportedLowRam = false)
        val lowRam = DeviceProfile.recommendModel(3_072, cores = 8, abi = DeviceAbi.ARM64, reportedLowRam = true)
        assertTrue(lowRam.maxModelSizeMb < normal.maxModelSizeMb)
        assertTrue(lowRam.limitedDevice)
    }

    @Test
    fun tiersFollowRecommendedSize() {
        assertEquals(ModelTier.CLOUD_ONLY, DeviceProfile.recommendModel(1_024, 4, DeviceAbi.ARMV7, true).tier)
        assertEquals(ModelTier.TINY, DeviceProfile.recommendModel(3_072, 4, DeviceAbi.ARMV7, false).tier)
        assertEquals(ModelTier.SMALL, DeviceProfile.recommendModel(4_096, 6, DeviceAbi.ARM64, false).tier)
        assertEquals(ModelTier.LARGE, DeviceProfile.recommendModel(16_384, 8, DeviceAbi.ARM64, false).tier)
    }

    @Test
    fun recommendationNeverExceedsBounds() {
        val tiny = DeviceProfile.recommendModel(512, 2, DeviceAbi.ARMV7, true)
        val huge = DeviceProfile.recommendModel(65_536, 16, DeviceAbi.X86_64, false)
        assertEquals(DeviceProfile.MIN_RECOMMENDED_MB, tiny.maxModelSizeMb)
        assertEquals(DeviceProfile.MAX_RECOMMENDED_MB, huge.maxModelSizeMb)
    }

    @Test
    fun localTokenBudgetIsReducedOnLimitedDevices() {
        assertEquals(
            DeviceProfile.DEFAULT_LOCAL_TOKENS_LIMITED,
            DeviceProfile.defaultLocalPredictTokens(limitedDevice = true),
        )
        assertEquals(
            DeviceProfile.DEFAULT_LOCAL_TOKENS,
            DeviceProfile.defaultLocalPredictTokens(limitedDevice = false),
        )
    }
}
