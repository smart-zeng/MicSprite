plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "io.github.smartzeng.micsprite.all"
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":micsprite-core"))
    api(project(":micsprite-decoder-svga"))
    api(project(":micsprite-decoder-pag"))
    api(project(":micsprite-decoder-frames"))
}

apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
