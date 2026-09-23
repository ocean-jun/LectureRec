plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.lecturerec"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.lecturerec"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "2.1"

        // ONNX Runtime 会给每个 ABI 各带一份原生库，全带上 APK 直接翻倍。
        // 只保留 arm64-v8a（近十年的手机都是）；要支持 32 位老机型就在这里加回 armeabi-v7a。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug 密钥签名，方便直接侧载安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        // 内部自用工具，不因 lint 告警阻断 release 构建
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests {
            // android.jar 里的框架类在单元测试中只是会抛 "not mocked" 的桩。
            // 打开这个开关让它们返回默认值，这样调用了 Log.* 的生产代码也能被测到。
            isReturnDefaultValues = true
        }
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        // 语音模型不压缩，读取时省一次解压
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Silero VAD 推理
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    // Android 单元测试的 classpath 里没有 com.sun.net.httpserver，
    // 所以用 OkHttp 官方的 MockWebServer 来验证 HTTP 线格式。
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // android.jar 里的 org.json 只是会抛 "not mocked" 的桩，测试时需要真实现
    testImplementation("org.json:json:20231013")
}
