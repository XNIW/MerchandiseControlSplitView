import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

fun readLocalOrEnv(name: String): String =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty(name)?.trim().orEmpty()

fun String.toBuildConfigLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.example.merchandisecontrolsplitview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.merchandisecontrolsplitview"
        minSdk = 31
        targetSdk = 36 // CORRETTO: Aggiornato per corrispondere a compileSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "SUPABASE_URL",
            readLocalOrEnv("SUPABASE_URL").toBuildConfigLiteral()
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            readLocalOrEnv("SUPABASE_PUBLISHABLE_KEY").toBuildConfigLiteral()
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            readLocalOrEnv("GOOGLE_WEB_CLIENT_ID").toBuildConfigLiteral()
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

// CORRETTO: Imposta un toolchain JVM consistente per Java e Kotlin
kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    if (name == "testDebugUnitTest") {
        doFirst {
            val byteBuddyAgent = configurations
                .named("debugUnitTestRuntimeClasspath")
                .get()
                .files
                .single { it.name.startsWith("byte-buddy-agent") && it.extension == "jar" }

            jvmArgs("-javaagent:${byteBuddyAgent.absolutePath}")
        }
    }
}

val excelRecognitionAuditRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "excelRecognitionAudit" || taskName.endsWith(":excelRecognitionAudit")
}

val excelRecognitionDriveBatchAuditRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "excelRecognitionDriveBatchAudit" ||
        taskName.endsWith(":excelRecognitionDriveBatchAudit")
}

val excelRecognitionOracleV2Requested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "excelRecognitionOracleV2" || taskName.endsWith(":excelRecognitionOracleV2")
}

val excelRecognitionOracleLoopRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "excelRecognitionOracleLoop" || taskName.endsWith(":excelRecognitionOracleLoop")
}

tasks.register("excelRecognitionAudit") {
    group = "verification"
    description = "Runs the Excel header recognition audit against supplied XLSX files or bundled golden fixtures."
}

tasks.register("excelRecognitionDriveBatchAudit") {
    group = "verification"
    description = "Runs the read-only Drive supplier Excel recognition batch audit."
}

tasks.register("excelRecognitionOracleV2") {
    group = "verification"
    description = "Runs the independent raw-workbook oracle v2 validation for Excel recognition."
}

tasks.register("excelRecognitionOracleLoop") {
    group = "verification"
    description = "Runs the orchestrated Oracle v2/v3 loop over golden controls and a Drive sample."
}

afterEvaluate {
    tasks.named("excelRecognitionAudit").configure {
        dependsOn("testDebugUnitTest")
    }
    tasks.named("excelRecognitionDriveBatchAudit").configure {
        dependsOn("testDebugUnitTest")
    }
    tasks.named("excelRecognitionOracleV2").configure {
        dependsOn("testDebugUnitTest")
    }
    tasks.named("excelRecognitionOracleLoop").configure {
        dependsOn("testDebugUnitTest")
    }

    if (excelRecognitionAuditRequested) {
        tasks.named<Test>("testDebugUnitTest").configure {
            filter {
                includeTestsMatching("*ExcelRecognitionAudit*")
            }
            systemProperty(
                "excelAudit.files",
                providers.gradleProperty("excelAudit.files").orNull.orEmpty()
            )
            systemProperty(
                "excelAudit.reportDir",
                layout.buildDirectory.dir("reports/excelRecognitionAudit").get().asFile.absolutePath
            )
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }

    if (excelRecognitionDriveBatchAuditRequested) {
        tasks.named<Test>("testDebugUnitTest").configure {
            filter {
                includeTestsMatching("*ExcelRecognitionDriveBatchAudit*")
            }
            systemProperty(
                "excelAudit.batchDirs",
                providers.gradleProperty("excelAudit.batchDirs").orNull.orEmpty()
            )
            systemProperty(
                "excelAudit.batchFiles",
                providers.gradleProperty("excelAudit.batchFiles").orNull.orEmpty()
            )
            systemProperty(
                "excelAudit.reportDir",
                layout.buildDirectory.dir("reports/excelRecognitionAudit").get().asFile.absolutePath
            )
            systemProperty("excelAudit.runDriveBatch", "true")
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }

    if (excelRecognitionOracleV2Requested) {
        tasks.named<Test>("testDebugUnitTest").configure {
            filter {
                includeTestsMatching("*ExcelRecognitionOracleV2*")
            }
            systemProperty("excelAudit.runOracleV2", "true")
            systemProperty(
                "excelAudit.reportDir",
                layout.buildDirectory.dir("reports/excelRecognitionAudit").get().asFile.absolutePath
            )
            systemProperty(
                "excelOracle.pinmarkFile",
                providers.gradleProperty("excelOracle.pinmarkFile").orNull.orEmpty()
            )
            systemProperty(
                "excelOracle.modalinaFile",
                providers.gradleProperty("excelOracle.modalinaFile").orNull.orEmpty()
            )
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }

    if (excelRecognitionOracleLoopRequested) {
        tasks.named<Test>("testDebugUnitTest").configure {
            filter {
                includeTestsMatching("*ExcelRecognitionAuditOracleLoop*")
            }
            systemProperty("excelAudit.runOracleLoop", "true")
            systemProperty(
                "excelAudit.reportDir",
                layout.buildDirectory.dir("reports/excelRecognitionAudit").get().asFile.absolutePath
            )
            systemProperty(
                "excelAudit.batchDirs",
                providers.gradleProperty("excelAudit.batchDirs").orNull.orEmpty()
            )
            systemProperty(
                "excelAudit.loopManualReviewDir",
                providers.gradleProperty("excelAudit.loopManualReviewDir").orNull.orEmpty()
            )
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    // BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.supabase.bom))

    // Core Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.work.runtime.ktx)

    // Foundation & Material
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.material)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)

    // Navigation, Activity, Core-ktx, Lifecycle
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.okhttp)

    // Room & Paging
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)

    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    // Extra libs
    implementation(libs.zxing)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.commons.collections4)
    implementation(libs.gson)
    implementation(libs.material)
    implementation(libs.supabase.kt)
    implementation(libs.supabase.realtime.kt)
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.core.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.jsoup)
}
