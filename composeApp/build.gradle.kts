import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    // Change 'composeCompiler' to 'compose.compiler'
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.kotlin.serialization)
}

// Define this only ONCE at the top level
val ktorVersion = libs.versions.ktor.get()

kotlin {
    // Sets Java 11 for the whole project safely
    jvmToolchain(11)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget()

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)

                // Foundation Dependencies [cite: 56]
                implementation(libs.sqldelight.runtime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)

                // Coil3 for image loading
                implementation("io.coil-kt.coil3:coil-compose:3.0.0-alpha07")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.sqldelight.driver.android)
                implementation(libs.androidx.activity.compose)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqldelight.driver.desktop)

                // Networking engine and logging for Desktop
                implementation("io.ktor:ktor-client-java:$ktorVersion")
                implementation("ch.qos.logback:logback-classic:1.4.14")
            }
        }
    }
}

android {
    namespace = "chat.donzi.localtavern"
    compileSdk = 34

    defaultConfig {
        applicationId = "chat.donzi.localtavern"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.desktop {
    application {
        // Change the placeholder to your actual package and class name
        mainClass = "chat.donzi.localtavern.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "LocalTavern"
            packageVersion = "1.0.0"
        }
    }
}

// Ensure this block is at the VERY BOTTOM
sqldelight {
    databases {
        create("LocalTavernDB") {
            packageName.set("chat.donzi.localtavern.data.database")
        }
    }
}