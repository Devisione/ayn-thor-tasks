plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aynthor.taskswap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aynthor.taskswap"
        minSdk = 30
        targetSdk = 34
        versionCode = 6
        versionName = "2.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подпись debug-ключом для установки с GitHub. Для Play Store создайте release keystore.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

@Suppress("DEPRECATION")
android.applicationVariants.configureEach {
    outputs.configureEach {
        val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val suffix = if (buildType.name == "release") "" else "-${buildType.name}"
        outputImpl.outputFileName = "ThorDisplaySwapper-v$versionName$suffix.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.flyfishxu:kadb:1.3.0")

    testImplementation("junit:junit:4.13.2")
}
