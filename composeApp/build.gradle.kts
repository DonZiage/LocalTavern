plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.kotlin.serialization)
}

val ktorVersion = libs.versions.ktor.get()

kotlin {
    jvmToolchain(17)

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
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.compose.materialIconsExtended)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)

            implementation(libs.cryptography.core)

            implementation(libs.coil3.coil.compose)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        getByName("desktopTest") {
            dependencies {
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
                // Decodes our generated QR codes with an independent reader to
                // prove they scan correctly (real scanner round-trip test).
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.google.zxing:javase:3.5.3")
            }
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            // BouncyCastle-backed provider: X25519/ChaCha20-Poly1305 are not in
            // the Android JCA below API 28, BC works on every supported API.
            implementation(libs.cryptography.provider.jdk.bc)
            // QR pairing scanner: CameraX preview + ML Kit barcode detection.
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.mlkit.barcode.scanning)
        }

        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.sqldelight.driver.desktop)

                implementation("io.ktor:ktor-client-java:$ktorVersion")
                implementation(libs.logback.classic)
                implementation(libs.cryptography.provider.jdk)
            }
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
            // CryptoKit + CommonCrypto (X25519, ChaCha20-Poly1305, HKDF).
            implementation(libs.cryptography.provider.optimal)
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
        versionCode = 55
        versionName = "0.5.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.desktop {
    application {
        mainClass = "chat.donzi.localtavern.MainKt"

        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "LocalTavern"
            packageVersion = "0.5.5"
        }
    }
}

sqldelight {
    databases {
        create("LocalTavernDB") {
            packageName.set("chat.donzi.localtavern.data.database")
            version = 8
        }
    }
}