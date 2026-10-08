import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version de l'app, issue du tag git en CI : ./gradlew assembleRelease -PappVersion=V1.02.03
// Format accepté : [v|V]xx.xxx.xxx (majeur sur 1 à 2 chiffres, mineur et patch sur 1 à 3).
// versionCode = MMmmmppp, donc croissant avec la version (99.999.999 au plus).
// Sans -PappVersion (build local) : 0.0.0-dev, versionCode 1.
val appVersionTag: String? = providers.gradleProperty("appVersion").orNull
val (appVersionName, appVersionCode) = parseAppVersion(appVersionTag)

fun parseAppVersion(tag: String?): Pair<String, Int> {
    if (tag.isNullOrBlank()) return "0.0.0-dev" to 1
    val match = Regex("""^[vV]?(\d{1,2})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(tag)
        ?: throw GradleException("Version invalide « $tag » : attendu Vxx.xxx.xxx")
    val (major, minor, patch) = match.destructured
    val code = major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    return tag.removePrefix("v").removePrefix("V") to code
}

// Clé de signature release, fournie par la CI (secrets GitHub) via des variables d'environnement.
// Absente (build local) : la release est signée avec la clé debug.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "fr.midiland.nextclouddream"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.midiland.nextclouddream"
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
    testOptions {
        // Tests JVM purs : les appels Android non simulés (Log…) renvoient des valeurs par défaut
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

    // Uniquement pour migrer les réglages des premières versions (LegacySettingsMigration) ;
    // le mot de passe est désormais chiffré directement avec l'Android Keystore

    // Logs
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    // Implémentations réelles de org.json et XmlPullParser (simples bouchons dans android.jar)
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
