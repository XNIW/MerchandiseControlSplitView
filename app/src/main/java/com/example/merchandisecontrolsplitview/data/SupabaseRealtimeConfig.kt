package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.BuildConfig

/**
 * Configurazione minima del client Supabase Realtime per task 010.
 *
 * Le chiavi arrivano da `BuildConfig`, popolato via env/local.properties per non
 * introdurre segreti nel repository. Se i valori sono assenti, il subscriber resta
 * disabilitato e l'app continua a funzionare in puro offline-first.
 */
data class SupabaseRealtimeConfig(
    val projectUrl: String,
    val publishableKey: String
) {
    val isEnabled: Boolean
        get() = projectUrl.isNotBlank() && publishableKey.isNotBlank()

    companion object {
        fun fromBuildConfig(): SupabaseRealtimeConfig =
            SupabaseRealtimeConfig(
                projectUrl = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )
    }
}
