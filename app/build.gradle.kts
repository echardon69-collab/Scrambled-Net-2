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

    // Saveurs de distribution (14 nov. 2026) : GitHub garde l'auto-mise
    // à jour (permission REQUEST_INSTALL_PACKAGES + Updater, APK
    // « github ») ; Play l'INTERDIT pour les self-updates (politique
    // « Use of the REQUEST_INSTALL_PACKAGES permission » : no
    // self-updates) — l'APK « play » retire la permission du
    // manifeste fusionné (src/play/AndroidManifest.xml,
    // tools:node="remove") et coupe la vérification de mise à jour à
    // la construction (Distribution.UPDATER_ENABLED). MÊME identité
    // d'application (pas de suffixe) : une seule app, deux canaux.
    // ATTENTION : avec des saveurs, les tâches Gradle changent de nom
    // (testGithubDebugUnitTest, lintPlayDebug...), la CI est à jour.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
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
