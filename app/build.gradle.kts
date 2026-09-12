plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


android {
    namespace = "com.readqurantoday.quran"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readqurantoday.quran"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    /* The page fonts live in their own source set so they are packaged at the
       root of assets alongside anything in src/main/assets. */
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "src/main/fonts-ttf")
        }
    }

    /* TTF files are read by memory-mapping the asset — compressing them would
       both waste build time and make memory-mapping impossible. */
    androidResources {
        noCompress += listOf("ttf")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    /* The pages, and the index. It arrived on ViewPager2's coat-tails until
       the reader stopped using one, so it is asked for by name now. */
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    /* The native reader's paging: the platform's own, so the gesture is the
       platform's too. */
    /* WindowInsetsControllerCompat, for the system bars. It arrives with
       appcompat anyway; named here because this module uses it directly. */
    implementation("androidx.core:core-ktx:1.13.1")
    /* The audio player. Android's MediaPlayer.seekTo() fails silently on VBR
       (variable-bit-rate) MP3 streams because it cannot calculate byte offsets
       without a fixed bitrate. ExoPlayer parses MPEG frames to find accurate
       positions in VBR files, the same way a browser's audio.currentTime works.
       It also accepts seekTo() before prepare(), so the async race that required
       pendingPlay / pendingStart flags is gone: the player simply arrives at the
       asked-for word the moment it is ready. */
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    /* MediaSessionCompat and NotificationCompat.MediaStyle for the player
       notification shown in the shade and on the lock screen. */
    implementation("androidx.media:media:1.7.0")
}
