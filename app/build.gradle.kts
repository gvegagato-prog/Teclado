plugins {
    id("com.android.application")
}

android {
    namespace = "app.teclado"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.teclado"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Clave de depuración fija (en el repo) para que las actualizaciones se instalen encima.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Los diccionarios ya son texto plano pequeño; evita que AAPT los comprima y los haga más lentos de leer.
    androidResources {
        noCompress += "tsv"
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
