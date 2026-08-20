import java.net.Inet4Address
import java.net.NetworkInterface

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

/**
 * en0 인터페이스(macOS Wi-Fi)에서 IPv4 주소를 읽어 반환합니다.
 * 인터페이스를 찾을 수 없거나 오류가 발생하면 null을 반환합니다.
 */
fun getEn0IpAddress(): String? {
    return try {
        NetworkInterface.getByName("en0")
            ?.inetAddresses
            ?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}

android {
    namespace = "com.rouddy.smstransmitter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rouddy.smstransmitter"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 1. 시스템 환경 변수 우선 적용
        // SMS_TRANSMITTER_BASE_URL 환경변수가 설정되어 있으면 그 값을 사용하고,
        // 없으면 en0 인터페이스(macOS Wi-Fi)에서 IP를 자동 감지합니다.
        // CI/서버 환경: SMS_TRANSMITTER_BASE_URL 환경변수를 명시적으로 설정하세요.
        // 로컬 개발:   환경변수 없이 빌드하면 자동으로 로컬 IP가 적용됩니다.
        val envApiUrl = System.getenv("SMS_TRANSMITTER_BASE_URL")
            ?: run {
                val detectedIp = getEn0IpAddress()
                if (detectedIp != null) {
                    println(">>> [auto] en0 IP 자동 감지: $detectedIp")
                    "http://$detectedIp"
                } else {
                    println(">>> [auto] en0 IP를 찾을 수 없습니다. 기본값 사용.")
                    "http://0.0.0.0"
                }
            }
        val envApiPort = System.getenv("SMS_TRANSMITTER_PORT") ?: "54323"

        println(">>> BASE_URL = $envApiUrl")

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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.2")

    // Retrofit + RxJava3
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:adapter-rxjava3:2.11.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")

    testImplementation("junit:junit:4.13.2")
}
