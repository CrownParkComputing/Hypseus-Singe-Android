plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.hypseus.singe"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.hypseus.singe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GAME_PROFILE", "\"multi\"")
        buildConfigField("boolean", "LOCK_GAME_SELECTION", "false")
        buildConfigField("String", "LOCKED_GAME_ID", "\"\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "edition"
    productFlavors {
        create("hypseus") {
            dimension = "edition"
            buildConfigField("String", "GAME_PROFILE", "\"multi\"")
            buildConfigField("boolean", "LOCK_GAME_SELECTION", "false")
            buildConfigField("String", "LOCKED_GAME_ID", "\"\"")
            resValue("string", "app_name", "Hypseus Android")
        }

        create("spaceace") {
            dimension = "edition"
            applicationId = "org.hypseus.singe.spaceace"
            versionNameSuffix = "-spaceace"
            buildConfigField("String", "GAME_PROFILE", "\"spaceace\"")
            buildConfigField("boolean", "LOCK_GAME_SELECTION", "true")
            buildConfigField("String", "LOCKED_GAME_ID", "\"ace\"")
            resValue("string", "app_name", "Space Ace")
        }

        create("dle") {
            dimension = "edition"
            applicationId = "org.hypseus.singe.dle"
            versionNameSuffix = "-dle"
            buildConfigField("String", "GAME_PROFILE", "\"dle\"")
            buildConfigField("boolean", "LOCK_GAME_SELECTION", "true")
            buildConfigField("String", "LOCKED_GAME_ID", "\"dle\"")
            resValue("string", "app_name", "Dragon's Lair Extended")
        }

        create("dl2e") {
            dimension = "edition"
            applicationId = "org.hypseus.singe.dl2e"
            versionNameSuffix = "-dl2e"
            buildConfigField("String", "GAME_PROFILE", "\"dl2e\"")
            buildConfigField("boolean", "LOCK_GAME_SELECTION", "true")
            buildConfigField("String", "LOCKED_GAME_ID", "\"dl2e\"")
            resValue("string", "app_name", "Dragon's Lair II Extended")
        }
    }

    packaging {
        jniLibs {
              useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
