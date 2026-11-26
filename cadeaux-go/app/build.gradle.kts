plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.discolow.cadeauxgo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.discolow.cadeauxgo"
        // AccessibilityService.takeScreenshot() existe depuis Android 11.
        minSdk = 30
        targetSdk = 35
        versionCode = 20
        versionName = "3.4"
    }

    // Clé de signature fixe, versionnée : les mises à jour s'installent par-dessus l'ancienne version.
    // (C'est la clé qui a signé toutes les versions précédentes de Cadeaux GO.)
    signingConfigs {
        create("cadeauxgo") {
            storeFile = file("cadeaux-go.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("cadeauxgo")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("cadeauxgo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Les aperçus d'interface (Robolectric, ~200 Mo à télécharger) seulement sur demande.
            if (System.getenv("PREVIEW") != "1") it.exclude("**/PreviewTest*")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Aperçus de l'interface rendus sur PC (test lancé seulement avec PREVIEW=1).
    testImplementation("org.robolectric:robolectric:4.14.1")
}
