plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version de l'app, issue du tag git en CI : ./gradlew assembleRelease -PappVersion=V1.02.03-rc.04
// Format accepté : [v|V]xx.xx.xx ou [v|V]xx.xx.xx-rc.xx (1 ou 2 chiffres par partie).
// versionCode = MMmmpprr : une release finale (rr = 99) passe après toutes ses RC (rr = 0..98).
// Sans -PappVersion (build local) : 0.0.0-dev, versionCode 1.
val appVersionTag: String? = providers.gradleProperty("appVersion").orNull
val (appVersionName, appVersionCode) = parseAppVersion(appVersionTag)

fun parseAppVersion(tag: String?): Pair<String, Int> {
    if (tag.isNullOrBlank()) return "0.0.0-dev" to 1
    val match = Regex("""^[vV]?(\d{1,2})\.(\d{1,2})\.(\d{1,2})(?:-rc\.(\d{1,2}))?$""").matchEntire(tag)
        ?: throw GradleException("Version invalide « $tag » : attendu Vxx.xx.xx ou Vxx.xx.xx-rc.xx")
    val (major, minor, patch, rc) = match.destructured
    val rcNumber = rc.toIntOrNull()
    if (rcNumber != null && rcNumber > 98) throw GradleException("Numéro de RC trop grand (max rc.98) : $tag")
    val code = major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (rcNumber ?: 99)
    return tag.removePrefix("v").removePrefix("V") to code
}

// Clé de signature release, fournie par la CI (secrets GitHub) via des variables d'environnement.
// Absente (build local) : la release est signée avec la clé debug.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.nextclouddream"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nextclouddream"
        // Android 9 (Pie) : Mi Box S et décodage HEIF natif
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 : supprime le code inutilisé des bibliothèques (APK ~10x plus léger),
            // important sur la Mi Box dont le stockage est très limité
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Clé release en CI, clé debug en local (usage perso en sideload)
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // AndroidX de base
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Chargement d'images (downsampling, EXIF, HEIF via le décodeur système)
    implementation("io.coil-kt:coil:2.7.0")

    // Lecture des métadonnées EXIF (date de prise de vue, GPS), y compris HEIC
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // HTTP / WebDAV (PROPFIND fait à la main avec OkHttp, pas besoin de Sardine)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Rafraîchissement périodique du cache
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Stockage chiffré des identifiants
    implementation("androidx.security:security-crypto:1.1.0")

    // Logs
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
}
