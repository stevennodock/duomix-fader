// Module applicatif DuoMix
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dirtwing.duomix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dirtwing.duomix"
        minSdk = 31
        targetSdk = 36
        // Sert aussi de version du UserService Shizuku : à incrémenter dès que
        // IMixerService.aidl change, sinon l'ancien processus shell est réutilisé
        versionCode = 4
        versionName = "0.2.1"
    }

    androidResources {
        // Déclare en/fr/es au système (choix de langue par app, Android 13+) ;
        // la langue par défaut est définie dans res/resources.properties
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        // AIDL nécessaire pour l'interface du UserService Shizuku
        aidl = true
        // BuildConfig (APPLICATION_ID, DEBUG, VERSION_CODE) utilisé par les UserServiceArgs
        buildConfig = true
    }

    // Signature : signing.properties (hors dépôt) pointe vers le keystore personnel ;
    // sans ce fichier, on retombe sur la clé de debug standard.
    val signingProps = Properties().apply {
        val file = rootProject.file("signing.properties")
        if (file.canRead()) file.inputStream().use { load(it) }
    }
    val personalSigning = if (signingProps.isEmpty) signingConfigs.getByName("debug") else
        signingConfigs.create("personal") {
            storeFile = file(signingProps.getProperty("KEYSTORE_FILE"))
            storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
            keyAlias = signingProps.getProperty("KEYSTORE_ALIAS")
            keyPassword = signingProps.getProperty("KEYSTORE_ALIAS_PASSWORD")
        }

    buildTypes {
        debug {
            signingConfig = personalSigning
        }
        release {
            signingConfig = personalSigning
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// Conformité licences : LICENSE et NOTICE de la racine sont embarqués tels quels dans
// l'APK (assets/licenses/) et affichés par LicensesScreen — une seule source de vérité.
val licenseAssetsDir = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenseAssets = tasks.register<Copy>("copyLicenseAssets") {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    into(licenseAssetsDir.map { it.dir("licenses") })
}
android.sourceSets.getByName("main").assets.srcDir(licenseAssetsDir)
tasks.named("preBuild") { dependsOn(copyLicenseAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Shizuku — accès aux privilèges shell (ADB) sans root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Contournement de la liste noire des API cachées (utilisé dans le UserService)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
