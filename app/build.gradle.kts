import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.worktime.trace"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.worktime.trace"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // 签名信息从 local.properties 读取（该文件不入库，模板见 local.properties.example）；
    // 未配置时回退 debug 签名，功能不受影响（仅高德地图/定位鉴权需匹配 SHA1）。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseKsPath = localProps.getProperty("wtStoreFile", "keystore/worktime.jks")
    val releaseKs = rootProject.file(releaseKsPath)
    val releasePwd = localProps.getProperty("wtStorePassword", "")
    val releaseConfigured = releaseKs.exists() && releasePwd.isNotBlank()

    signingConfigs {
        create("release") {
            if (releaseConfigured) {
                storeFile = releaseKs
                storePassword = releasePwd
                keyAlias = localProps.getProperty("wtKeyAlias", "worktime")
                keyPassword = localProps.getProperty("wtKeyPassword", releasePwd)
            }
        }
    }

    buildTypes {
        debug {
            // debug 也用项目签名：保证高德 Key 的 SHA1 匹配（任何机构建签名一致）
            signingConfig = if (releaseConfigured) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseConfigured) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 高德 3D 地图 SDK（选点）；10.x 已内嵌定位能力（com.amap.api.location.*），
    // 不依赖 Google Play Services，适配国产 ROM。切勿再单独引入 com.amap.api:location，会类冲突。
    implementation("com.amap.api:3dmap:10.0.600")
}
