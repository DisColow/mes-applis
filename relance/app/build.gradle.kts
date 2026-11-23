plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.mesapplis.relance"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.mesapplis.relance"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Clé de signature fixe, versionnée avec l'appli : l'empreinte SHA-1 ne change jamais,
    // ce qui est indispensable pour la connexion Google Tasks (déclarée dans Google Cloud)
    // et pour pouvoir installer les mises à jour par-dessus l'ancienne version.
    signingConfigs {
        create("relance") {
            storeFile = file("relance.keystore")
            storePassword = "relance-android"
            keyAlias = "relance"
            keyPassword = "relance-android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("relance")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("relance")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
}
