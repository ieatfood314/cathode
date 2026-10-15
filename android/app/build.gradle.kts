plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ruminate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ruminate.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            // Minify off for v0.1.x: guarantees a working first APK without
            // R8/serialization tuning. Can enable later with keep rules.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the release keystore when env provides it
            // (RUMINATE_KEYSTORE_PATH/PASSWORD/KEY_ALIAS/KEY_PASSWORD);
            // otherwise plain `gradle assembleRelease` falls back to the
            // debug key (fine for personal use, but keep one machine for
            // Obtainium updates so the signature stays stable).
            val ksPath = System.getenv("RUMINATE_KEYSTORE_PATH")
            if (ksPath != null) {
                signingConfig = signingConfigs.create("ruminateRelease") {
                    storeFile = file(ksPath)
                    storePassword = System.getenv("RUMINATE_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("RUMINATE_KEY_ALIAS") ?: "ruminate"
                    keyPassword = System.getenv("RUMINATE_KEY_PASSWORD")
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
