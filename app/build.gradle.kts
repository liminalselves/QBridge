import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 本机配置（local.properties，不入库）：签名与离线预置包路径
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 离线预置包：大体积文件存放于工作区 dat/（不入库），其下 preload/ 子目录里的文件
// 会在构建时并入 assets/preload。目录不存在（如开源 clone）则跳过，
// 应用初始化时按在线路径自动下载。dat 根目录请只保留 preload/。
val preloadAssetsDir = rootProject.file(localProps.getProperty("preload.assetsDir", "../dat"))

android {
    namespace = "com.aliya2qq.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aliya2qq.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.6.5-opensource"
        // 内部分发：仅 arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (preloadAssetsDir.resolve("preload").isDirectory) {
        sourceSets {
            getByName("main") {
                assets.srcDir(preloadAssetsDir)
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(localProps.getProperty("release.storeFile", ""))
            storePassword = localProps.getProperty("release.storePassword", "")
            keyAlias = localProps.getProperty("release.keyAlias", "")
            keyPassword = localProps.getProperty("release.keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            // 必须落盘为可执行文件，否则 File.isFile 在 extractNativeLibs=false 时找不到
            useLegacyPackaging = true
        }
    }

    androidResources {
        // deb/zip/tar.xz 已是压缩格式，禁止二次压缩
        noCompress += listOf("deb", "zip", "xz", "gz", "zst", "tar")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    // 离线解压 tar.xz（不依赖 jniLibs/tar）
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
