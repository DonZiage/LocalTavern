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

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Swapped from compose.* to your libs.versions.toml definitions
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            // Pinned to the last supported version as requested by the warning
            implementation(libs.compose.materialIconsExtended)

            // Foundation Dependencies
            implementation(libs.sqldelight.runtime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            // Coil3 for image loading
            implementation(libs.coil3.coil.compose)
        }

        // Modern syntax: directly accessing androidMain.dependencies
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }


        // Modern syntax: directly accessing desktopMain.dependencies
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqldelight.driver.desktop)

                // Networking engine and logging for Desktop
                implementation("io.ktor:ktor-client-java:$ktorVersion")
                implementation(libs.logback.classic)
            }
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "chat.donzi.localtavern"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "chat.donzi.localtavern"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
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