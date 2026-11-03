plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
}

android {
    namespace = "com.github.smartzeng.micsprite.compose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":micsprite-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
}

apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
