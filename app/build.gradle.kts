import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload key details live outside git, in keystore.properties at the project root
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}


android {
    namespace = "com.readqurantoday.quran"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.readqurantoday.quran"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.1"
    }

    // Page fonts packaged at the root of assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "src/main/fonts-ttf")
        }
    }

    // Uncompressed so the fonts can be memory-mapped
    androidResources {
        noCompress += listOf("ttf")
    }

    // The in-app language switch needs both languages installed, whatever the phone's language
    bundle {
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Installs beside the Play version, which is signed with another key
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("upload")
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    // The pager and the index lists
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // WindowInsetsControllerCompat for the system bars
    implementation("androidx.core:core-ktx:1.19.0")
    // ExoPlayer seeks accurately in VBR MP3s, where MediaPlayer.seekTo() fails silently
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // MediaSession and MediaStyle for the player notification
    implementation("androidx.media:media:1.8.0")
    // HSV colour picker with a hex field
    implementation("com.jaredrummler:colorpicker:1.1.0")
}
