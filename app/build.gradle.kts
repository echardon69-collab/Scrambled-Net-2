plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.chardons.netscramble2"
    compileSdk = 37

    defaultConfig {
        // Identifiant définitif (ne plus le changer une fois publié sur F-Droid).
        applicationId = "net.chardons.netscramble2"
        // minSdk 24 = Android 7.0 : VectorDrawables à dégradés natifs
        // (<aapt:attr>) sans repli AppCompat, pour un rendu identique partout.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 : code plus petit et plus rapide — parfait pour les vieux appareils.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Tests unitaires JVM purs (aucun émulateur nécessaire) : moteur de jeu uniquement.
    testImplementation(libs.junit)
}
