package com.secrethero.neurocode.device

import com.secrethero.neurocode.ai.ModelCatalog
import com.secrethero.neurocode.ai.ModelSpec

/**
 * Подбор моделей каталога под характеристики телефона (0.9.1).
 *
 * Рекомендация считается с запасом под контекст и систему: на 32-битных и low-RAM
 * устройствах предлагаются только компактные модели. Характеристики устройства
 * считаются локально ([DeviceSpecs]) и наружу не передаются.
 */
object ModelRecommender {

    /**
     * Модели каталога, отсортированные по пригодности для [profile]:
     * рекомендуемые первыми, затем остальные; слишком тяжёлые помечаются флагом [tooLarge]
     * (не скрываются, чтобы пользователь видел причину).
     */
    fun rank(catalog: List<ModelSpec>, profile: ModelRecommendation): List<ModelFit> =
        catalog
            .map { spec -> ModelFit(spec, tooLarge = spec.sizeMb > profile.maxModelSizeMb) }
            .sortedWith(compareByDescending<ModelFit> { it.spec.recommended && !it.tooLarge }
                .thenBy { it.tooLarge }
                .thenByDescending { it.spec.quality }
                .thenBy { it.spec.sizeMb })

    /** «Рекомендовано для этого телефона»: помещающиеся модели, рекомендуемые первыми. */
    fun recommendedFor(catalog: List<ModelSpec>, profile: ModelRecommendation): List<ModelSpec> =
        rank(catalog, profile)
            .filter { !it.tooLarge }
            .take(RECOMMENDED_COUNT)
            .map { it.spec }

    /** Все модели каталога, которые помещаются в память устройства. */
    fun fitting(catalog: List<ModelSpec>, profile: ModelRecommendation): List<ModelSpec> =
        catalog.filter { it.sizeMb <= profile.maxModelSizeMb }

    data class ModelFit(
        val spec: ModelSpec,
        val tooLarge: Boolean,
    )

    /** Сколько моделей показывать в блоке «Рекомендовано» (без ручного поиска). */
    const val RECOMMENDED_COUNT = 5
}
