package com.dosely.app.translate

/**
 * The complete catalog of ML Kit on-device translation languages (59 codes).
 * Values are the exact string codes accepted by
 * `TranslateLanguage.fromLanguageTag` / `TranslatorOptions`, kept as literals so
 * the list needs no dependency on companion constants.
 */
data class AppLanguage(
    val tag: String,
    val englishName: String,
    /** ML Kit language code, e.g. "en", "de", "zh-TW". */
    val mlkitCode: String,
)

object AppLanguages {
    val all: List<AppLanguage> = listOf(
        AppLanguage("af", "Afrikaans", "af"),
        AppLanguage("sq", "Albanian", "sq"),
        AppLanguage("ar", "Arabic", "ar"),
        AppLanguage("be", "Belarusian", "be"),
        AppLanguage("bn", "Bengali", "bn"),
        AppLanguage("bg", "Bulgarian", "bg"),
        AppLanguage("ca", "Catalan", "ca"),
        AppLanguage("zh-CN", "Chinese (Simplified)", "zh-CN"),
        AppLanguage("zh-TW", "Chinese (Traditional)", "zh-TW"),
        AppLanguage("hr", "Croatian", "hr"),
        AppLanguage("cs", "Czech", "cs"),
        AppLanguage("da", "Danish", "da"),
        AppLanguage("nl", "Dutch", "nl"),
        AppLanguage("en", "English", "en"),
        AppLanguage("eo", "Esperanto", "eo"),
        AppLanguage("et", "Estonian", "et"),
        AppLanguage("fi", "Finnish", "fi"),
        AppLanguage("fr", "French", "fr"),
        AppLanguage("gl", "Galician", "gl"),
        AppLanguage("ka", "Georgian", "ka"),
        AppLanguage("de", "German", "de"),
        AppLanguage("el", "Greek", "el"),
        AppLanguage("gu", "Gujarati", "gu"),
        AppLanguage("ht", "Haitian Creole", "ht"),
        AppLanguage("he", "Hebrew", "he"),
        AppLanguage("hi", "Hindi", "hi"),
        AppLanguage("hu", "Hungarian", "hu"),
        AppLanguage("is", "Icelandic", "is"),
        AppLanguage("id", "Indonesian", "id"),
        AppLanguage("ga", "Irish", "ga"),
        AppLanguage("it", "Italian", "it"),
        AppLanguage("ja", "Japanese", "ja"),
        AppLanguage("kn", "Kannada", "kn"),
        AppLanguage("ko", "Korean", "ko"),
        AppLanguage("lv", "Latvian", "lv"),
        AppLanguage("lt", "Lithuanian", "lt"),
        AppLanguage("mk", "Macedonian", "mk"),
        AppLanguage("ms", "Malay", "ms"),
        AppLanguage("mt", "Maltese", "mt"),
        AppLanguage("mr", "Marathi", "mr"),
        AppLanguage("no", "Norwegian", "no"),
        AppLanguage("fa", "Persian", "fa"),
        AppLanguage("pl", "Polish", "pl"),
        AppLanguage("pt", "Portuguese", "pt"),
        AppLanguage("ro", "Romanian", "ro"),
        AppLanguage("ru", "Russian", "ru"),
        AppLanguage("sk", "Slovak", "sk"),
        AppLanguage("sl", "Slovenian", "sl"),
        AppLanguage("es", "Spanish", "es"),
        AppLanguage("sw", "Swahili", "sw"),
        AppLanguage("sv", "Swedish", "sv"),
        AppLanguage("tl", "Tagalog (Filipino)", "tl"),
        AppLanguage("ta", "Tamil", "ta"),
        AppLanguage("te", "Telugu", "te"),
        AppLanguage("th", "Thai", "th"),
        AppLanguage("tr", "Turkish", "tr"),
        AppLanguage("uk", "Ukrainian", "uk"),
        AppLanguage("ur", "Urdu", "ur"),
        AppLanguage("vi", "Vietnamese", "vi"),
        AppLanguage("cy", "Welsh", "cy"),
    )

    fun byTag(tag: String): AppLanguage? = all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    fun byMlkitCode(code: String): AppLanguage? = all.firstOrNull { it.mlkitCode == code }
}
