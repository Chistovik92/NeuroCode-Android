package com.secrethero.neurocode.device

/**
 * CPU architectures supported by the app builds.
 */
enum class DeviceAbi(val abiName: String) {
    ARM64("arm64-v8a"),
    ARMV7("armeabi-v7a"),
    X86_64("x86_64"),
    X86("x86"),
    UNKNOWN("unknown"),
    ;

    val is32Bit: Boolean
        get() = this == ARMV7 || this == X86

    companion object {
        fun fromSupportedAbis(supportedAbis: List<String>): DeviceAbi = supportedAbis
            .firstNotNullOfOrNull { raw ->
                entries.firstOrNull { it.abiName.equals(raw.trim(), ignoreCase = true) }
            }
            ?: UNKNOWN

        fun fromAbiName(abiName: String): DeviceAbi =
            fromSupportedAbis(listOf(abiName))
    }
}

/** Coarse model size classes used for user-facing recommendations. */
enum class ModelTier {
    CLOUD_ONLY,
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
}

data class ModelRecommendation(
    /** Largest GGUF file size that is expected to fit into memory comfortably. */
    val maxModelSizeMb: Long,
    val tier: ModelTier,
    /** True when 32-bit address space or low RAM limits local inference. */
    val limitedDevice: Boolean,
)

/**
 * Pure heuristics that map device capabilities to local-model advice.
 * Kept free of Android imports so it can be unit-tested on the JVM.
 */
object DeviceProfile {

    const val MB = 1024L * 1024L
    const val MIN_RECOMMENDED_MB = 250L
    const val MAX_RECOMMENDED_MB = 6_000L

    /**
     * Share of total RAM that an app may realistically use for weights plus KV cache.
     * Low-RAM devices keep a much larger share for the system.
     */
    const val USABLE_FRACTION = 0.55
    const val USABLE_FRACTION_LOW_RAM = 0.35

    /** Headroom reserved inside the usable budget for context/KV cache and runtime overhead. */
    const val MODEL_SHARE_OF_USABLE = 0.75

    /** 32-bit processes can only address ~3 GB; stay well below the ceiling. */
    const val THIRTY_TWO_BIT_CEILING_MB = 2_200L
    const val LOW_RAM_TOTAL_MB = 2_048L

    fun isLowRam(totalMemoryMb: Long, reportedLowRam: Boolean): Boolean =
        reportedLowRam || totalMemoryMb in 1..LOW_RAM_TOTAL_MB

    fun usableMemoryMb(totalMemoryMb: Long, lowRam: Boolean): Long {
        if (totalMemoryMb <= 0) return MIN_RECOMMENDED_MB
        val fraction = if (lowRam) USABLE_FRACTION_LOW_RAM else USABLE_FRACTION
        return (totalMemoryMb * fraction).toLong().coerceAtLeast(MIN_RECOMMENDED_MB)
    }

    fun recommendModel(
        totalMemoryMb: Long,
        cores: Int,
        abi: DeviceAbi,
        reportedLowRam: Boolean,
    ): ModelRecommendation {
        val lowRam = isLowRam(totalMemoryMb, reportedLowRam)
        val usable = usableMemoryMb(totalMemoryMb, lowRam)
        val abiCeiling = if (abi.is32Bit) THIRTY_TWO_BIT_CEILING_MB else MAX_RECOMMENDED_MB
        val coreFactor = coreFactor(cores)
        val byMemory = (usable * MODEL_SHARE_OF_USABLE).toLong()
        val maxModelSizeMb = (byMemory * coreFactor).toLong()
            .coerceIn(MIN_RECOMMENDED_MB, abiCeiling)
        return ModelRecommendation(
            maxModelSizeMb = maxModelSizeMb,
            tier = tierFor(maxModelSizeMb),
            limitedDevice = lowRam || abi.is32Bit,
        )
    }

    private fun coreFactor(cores: Int): Double = when {
        cores <= CORES_FEW -> CORE_FACTOR_FEW
        cores <= CORES_SOME -> CORE_FACTOR_SOME
        else -> CORE_FACTOR_MANY
    }

    private fun tierFor(maxModelSizeMb: Long): ModelTier = when {
        maxModelSizeMb < TIER_TINY_MAX_MB -> ModelTier.CLOUD_ONLY
        maxModelSizeMb < TIER_SMALL_MAX_MB -> ModelTier.TINY
        maxModelSizeMb < TIER_MEDIUM_MAX_MB -> ModelTier.SMALL
        maxModelSizeMb < TIER_LARGE_MAX_MB -> ModelTier.MEDIUM
        else -> ModelTier.LARGE
    }

    /** Default token budget for local generation on constrained devices. */
    fun defaultLocalPredictTokens(limitedDevice: Boolean): Int =
        if (limitedDevice) DEFAULT_LOCAL_TOKENS_LIMITED else DEFAULT_LOCAL_TOKENS

    const val DEFAULT_LOCAL_TOKENS = 768
    const val DEFAULT_LOCAL_TOKENS_LIMITED = 384

    private const val CORES_FEW = 4
    private const val CORES_SOME = 6
    private const val CORE_FACTOR_FEW = 0.8
    private const val CORE_FACTOR_SOME = 1.0
    private const val CORE_FACTOR_MANY = 1.15
    private const val TIER_TINY_MAX_MB = 700L
    private const val TIER_SMALL_MAX_MB = 1_500L
    private const val TIER_MEDIUM_MAX_MB = 3_000L
    private const val TIER_LARGE_MAX_MB = 5_000L
}
