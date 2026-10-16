package com.example.merchandisecontrolsplitview

/** Explicit absent-configuration JVM profile, independent of local developer configuration. */
class UnconfiguredTestApplication : MerchandiseControlApplication() {
    internal override val supabaseConfiguration = ApplicationSupabaseConfiguration("", "")
}
