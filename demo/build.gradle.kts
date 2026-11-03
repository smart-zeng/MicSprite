plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.github.smartzeng.micsprite.demo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.smartzeng.micsprite.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources {
            merges += "META-INF/services/com.github.smartzeng.micsprite.core.MicSpriteDecoder"
        }
    }
}

dependencies {
    implementation(project(":micsprite-all"))
    implementation(project(":micsprite-compose"))
    implementation(project(":micsprite-view"))
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
