package com.secrethero.neurocode.ui

/**
 * Различение одинаковых названий проектов и диалогов: короткий идентификатор из UUID показывается
 * только там, где названия совпадают, иначе он был бы лишним шумом.
 */
object NameLabels {
    private const val SHORT_ID_LENGTH = 4
    private val whitespace = Regex("\\s+")

    /** Первые символы UUID без дефисов; одинаковый результат для одного и того же UUID. */
    fun shortId(uuid: String): String = uuid.replace("-", "").take(SHORT_ID_LENGTH)

    /** Название для сравнения: без регистра, лишних пробелов по краям и внутри. */
    fun normalize(name: String): String = name.trim().lowercase().replace(whitespace, " ")

    /** Нормализованные названия, которые встречаются больше одного раза. */
    fun duplicated(names: List<String>): Set<String> =
        names.map(::normalize).groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** Название с коротким идентификатором, если такое название есть у нескольких элементов. */
    fun label(name: String, id: String, duplicated: Set<String>): String =
        if (normalize(name) in duplicated) "$name · ${shortId(id)}" else name

    /** Есть ли среди [existing] название, совпадающее с [candidate] после нормализации. */
    fun collides(candidate: String, existing: List<String>): Boolean {
        val normalized = normalize(candidate)
        return normalized.isNotEmpty() && existing.any { normalize(it) == normalized }
    }
}
