plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "com.rouddy.smstransmitter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rouddy.smstransmitter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 1. 시스템 환경 변수 읽기 (값이 없을 경우를 대비해 Elvis 연산자로 기본값 지정 추천)
        val envApiUrl = System.getenv("SMS_TRANSMITTER_BASE_URL") ?: "http://0.0.0.0"
        val envApiPort = System.getenv("SMS_TRANSMITTER_PORT") ?: "54323"

        buildConfigField("String", "BASE_URL", "\"$envApiUrl\"")
        buildConfigField("int", "PORT", envApiPort)
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

secrets {
    propertiesFileName = "secrets.properties"
    defaultPropertiesFileName = "secrets.defaults.properties"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.2")

    // Retrofit + RxJava3
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:adapter-rxjava3:2.11.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")

    testImplementation("junit:junit:4.13.2")
}
