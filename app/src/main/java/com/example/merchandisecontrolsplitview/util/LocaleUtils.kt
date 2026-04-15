// LocaleUtils.kt
package com.example.merchandisecontrolsplitview.util

import android.content.Context
import java.util.Locale

fun setLocale(context: Context, language: String): Context {
    val locale = Locale.forLanguageTag(language)
    Locale.setDefault(locale)

    val config = context.resources.configuration
    config.setLocale(locale)

    return context.createConfigurationContext(config)
}