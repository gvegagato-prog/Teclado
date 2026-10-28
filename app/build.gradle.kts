import java.util.Properties

plugins {
    id("com.android.application")
}

// Firma de la versión release: fuera del repo (es público). Ver README.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.teclado"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.teclado"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2"
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
            isMinifyEnabled = true
            isShrinkResources = true
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
            // Opcional: usar jars de Robolectric ya descargados (entornos sin acceso directo a Maven Central).
            providers.gradleProperty("robolectricDir").orNull?.let {
                t.systemProperty("robolectric.offline", "true")
                t.systemProperty("robolectric.dependency.dir", it)
            }
            providers.gradleProperty("previewDir").orNull?.let { t.systemProperty("preview.dir", it) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
