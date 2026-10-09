import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de release: keystore.properties (no versionado) apunta al .jks
// Versión base; CI le agrega el número de build (1.1 -> 1.1.7). Subirla a mano para cambios grandes.
val baseVersion = "1.1"

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.tachito"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tachito"
        minSdk = 30
        targetSdk = 34
        // CI pasa -PbuildNumber=<n> en cada merge a main: versionCode siempre crece y el APK se instala encima.
        val buildNumber = (findProperty("buildNumber") as String?)?.toInt()
        versionCode = if (buildNumber != null) 100 + buildNumber else 2
        versionName = baseVersion + (buildNumber?.let { ".$it" } ?: "")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")
    testImplementation("junit:junit:4.13.2")
}
