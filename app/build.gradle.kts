@file:Suppress("UNUSED_EXPRESSION")
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.ngontol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ngontol"
        minSdk = 24
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 148
        versionName = "148"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing configs - GANTI INI SEBELUM RELEASE!
    signingConfigs {
        // Debug config (sudah ada default)
        getByName("debug") {
            // Ini pake default debug keystore
        }

        // Release config - UNCOMMENT DAN ISI INI UNTUK PRODUCTION!
        create("release") {
            // GANTI PATH KE KEYSTORE LO!
            storeFile = file("C:\\Users\\user\\KST")
            storePassword = "787898"
            keyAlias = "key0"
            keyPassword = "787898"

            // Atau bisa pake environment variable biar aman:
            // storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore.jks")
            // storePassword = System.getenv("KEYSTORE_PASSWORD")
            // keyAlias = System.getenv("KEY_ALIAS")
            // keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Debug build - no optimization
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }

        release {
            // OPTIMASI PENUH!
            isMinifyEnabled = true
            isShrinkResources = true  // Hapus resource yang ga kepake
            isDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // PILIH SALAH SATU:
            // 1. Untuk testing (pake debug key):
            signingConfig = signingConfigs.getByName("debug")

            // 2. Untuk production (UNCOMMENT ini & comment yang atas):
            // signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
        buildConfig = true
    }

    // Optimasi tambahan
    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Moshi
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Services
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Firebase (pake BOM biar version konsisten)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore")

    // Ktor
    implementation("io.ktor:ktor-client-android:2.3.12")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    // ThreeTen (Date/Time)
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.6")
}