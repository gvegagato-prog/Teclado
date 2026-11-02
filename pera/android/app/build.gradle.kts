import java.util.Properties

plugins {
    id("com.android.application")
}

// Firma de la versión release: fuera del repo. Sin keystore.properties se genera sin firmar.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.pera"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pera"
        minSdk = 31 // Android 12: permisos de Bluetooth modernos
        targetSdk = 36
        versionCode = 9
        versionName = "0.9"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin minificar: Pera llama por reflexión a clases internas del sistema y sobrescribe
            // métodos de interfaces ocultas; reducir el código podría renombrarlos sin avisar.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { t ->
            // Opcional: jars de Robolectric ya descargados (entornos sin acceso directo a Maven Central).
            providers.gradleProperty("robolectricDir").orNull?.let {
                t.systemProperty("robolectric.offline", "true")
                t.systemProperty("robolectric.dependency.dir", it)
            }
            providers.gradleProperty("previewDir").orNull?.let { t.systemProperty("preview.dir", it) }
        }
    }
}

dependencies {
    compileOnly(project(":stubs"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
