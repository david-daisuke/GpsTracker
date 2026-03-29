plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.gpsapp"
    // ★ 安定版の最新バージョン(35)にスッキリ統一しました
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gpsapp"
        minSdk = 24
        targetSdk = 35 // ★ ここも35に統一
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 位置情報
    implementation("com.google.android.gms:play-services-location:21.2.0")
    // ★ 完全無料の地図ライブラリ (osmdroid)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // ★ グラフ用ライブラリ
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}