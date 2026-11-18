import java.time.LocalDate
import java.time.ZoneOffset

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

        // Lot 17 — péremption (16-17 nov. 2026, décisions
        // d'eric) : Gradle n'estampille QUE la date de build
        // du jour (UTC, ISO yyyy-MM-dd) dans BuildConfig ;
        // TOUTE la logique de péremption vit dans
        // Peremption.kt, testable sur JVM. Versions de
        // développement (0.x.y) : 5 jours de grâce puis écran
        // de blocage pur ; releases (1.x.y et au-delà) :
        // illimitées.
        buildConfigField(
            "String",
            "DATE_BUILD",
            "\"" + LocalDate.now(ZoneOffset.UTC) + "\""
        )
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
        // Lot 17 : BuildConfig est coupé par défaut depuis
        // AGP 8 ; la date de build (DATE_BUILD) l'exige de
        // nouveau, aux côtés du viewBinding.
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // CI rouge du 17 nov. 2026 (lot Apprentissage) : les 34 chaînes
    // du mode Apprentissage vivent en anglais et français, la
    // complétion par delta du corse et de l'alsacien attend le
    // prochain tour de traduction (kits à régénérer — décision
    // d'eric, option A). validate_langues admet DEJA les langues
    // partielles ; lint, lui, refuse MissingTranslation par défaut
    // et a fait échouer la CI. La politique de la maison s'écrit
    // ici UNE fois : l'avertissement reste SIGNALÉ dans les
    // rapports lint, il n'échoue plus le build.
    lint {
        warning += "MissingTranslation"
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
