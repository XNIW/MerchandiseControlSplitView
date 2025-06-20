// LocaleUtils.kt
package com.example.merchandisecontrolsplitview.util

import android.content.Context
import java.util.Locale

fun setLocale(context: Context, language: String): Context {
    val locale = Locale(language) // Still need to address the deprecation of this constructor
    Locale.setDefault(locale)
    val config = context.resources.configuration

    // Assuming minSdkVersion is 24 or higher, you can remove the 'if' and 'else'
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}