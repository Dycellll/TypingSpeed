import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.dycel.typingspeed"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.dycel.typingspeed"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false

            val storeFilePath = project.findProperty("TYPING_SPEED_STORE_FILE")?.toString()
            val storePasswordValue = project.findProperty("TYPING_SPEED_STORE_PASSWORD")?.toString()
            val keyAliasValue = project.findProperty("TYPING_SPEED_KEY_ALIAS")?.toString()
            val keyPasswordValue = project.findProperty("TYPING_SPEED_KEY_PASSWORD")?.toString()

            if (
                storeFilePath != null &&
                storePasswordValue != null &&
                keyAliasValue != null &&
                keyPasswordValue != null
            ) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}