plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// google-services.json이 있을 때만 Firebase 설정을 적용 (없으면 푸시 없이 빌드됨)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("⚠️  app/google-services.json 없음 → 푸시(FCM) 없이 빌드합니다.")
}

android {
    namespace = "kr.hanidayo.eodi"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.hanidayo.eodi"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    // 가족 배포용 고정 서명 키: android/eodi-release.jks + 비밀번호
    //   (CI: EODI_KEYSTORE_PASSWORD 환경변수 / 맥: android/keystore-password.txt)
    // 키가 없으면 기기마다 다른 기본 디버그 키로 서명됨 → 업데이트 시 재설치 필요
    val ksFile = rootProject.file("eodi-release.jks")
    val ksPass = System.getenv("EODI_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("keystore-password.txt").takeIf { it.exists() }?.readText()?.trim()
    if (ksFile.exists() && ksPass != null) {
        signingConfigs.create("family") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "eodi"
            keyPassword = ksPass
        }
    } else {
        logger.warn("⚠️  고정 서명 키 없음 → 기본 디버그 키로 서명합니다.")
    }
    val family = signingConfigs.findByName("family")

    buildTypes {
        debug {
            if (family != null) signingConfig = family
        }
        release {
            isMinifyEnabled = false
            signingConfig = family ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
