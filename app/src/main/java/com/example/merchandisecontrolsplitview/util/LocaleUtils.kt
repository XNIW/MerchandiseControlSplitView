// LocaleUtils.kt
package com.example.merchandisecontrolsplitview.util

import android.content.Context
import java.util.Locale

fun setLocale(context: Context, language: String): Context {
    // CORREZIONE: Utilizza il metodo moderno e corretto per creare un Locale.
    val locale = Locale.forLanguageTag(language)

    // NOTA: Locale.setDefault è scoraggiato nelle app moderne, ma la riga
    // sottostante (createConfigurationContext) è il modo corretto di gestire
    // il locale a livello di contesto, quindi il tuo codice funzionerà bene.
    Locale.setDefault(locale)

    val config = context.resources.configuration
    config.setLocale(locale)

    return context.createConfigurationContext(config)
}