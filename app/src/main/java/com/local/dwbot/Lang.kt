package com.local.dwbot

import java.util.Locale

/**
 * The game language the templates have to match. The buttons are screenshots of the game's text, and every language
 * (and simplified vs traditional Chinese: 行军 / 行軍) looks different, so each language has its own template set.
 */
object Lang {
    const val AUTO = "auto"
    const val SIMPLIFIED = "zh-CN"
    const val TRADITIONAL = "zh-TW"
    const val ENGLISH = "en"
    const val KOREAN = "ko"

    /** Turns the saved preference into a concrete language; "auto" follows the phone's system language. */
    fun resolve(pref: String?, locale: Locale = Locale.getDefault()): String = when (pref) {
        SIMPLIFIED, TRADITIONAL, ENGLISH, KOREAN -> pref
        else -> when {
            isTraditional(locale) -> TRADITIONAL
            locale.language == "en" -> ENGLISH
            locale.language == "ko" -> KOREAN
            else -> SIMPLIFIED
        }
    }

    /** zh-Hant, or zh in Taiwan / Hong Kong / Macau; everything else (zh-CN, English, ...) uses the simplified set. */
    private fun isTraditional(l: Locale) =
        l.language == "zh" && (l.script == "Hant" || l.country in setOf("TW", "HK", "MO"))

    /** Asset folder with this language's own templates, or null for the default (simplified) set in templates/. */
    fun folder(lang: String): String? = when (lang) {
        TRADITIONAL -> "templates_tw"
        ENGLISH -> "templates_en"
        KOREAN -> "templates_ko"
        else -> null
    }

    /** Label for the settings screens. */
    fun label(pref: String?) = when (pref) {
        SIMPLIFIED -> "简体中文"
        TRADITIONAL -> "繁體中文"
        ENGLISH -> "English"
        KOREAN -> "한국어"
        else -> "自动（跟随系统）"
    }
}
