plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.example.hadashboard"
    // Compile with 36 to satisfy the new AndroidX libraries
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hadashboard"
        // minSdk 24 is perfect for Android 8 (SDK 26)
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.5"

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

    kotlinOptions {
        jvmTarget = "11"
    }

    // We use java.srcDirs to safely point to the proto folder
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/proto")
        }
    }
}

dependencies {
    // AndroidX Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // MQTT FIX: Added the library to fix 'Unresolved reference eclipse'
    implementation(libs.mqtt.client)

    // PROTOBUF FIXES:
    // 1. Provides descriptor.proto for the compiler
    protobuf("com.google.protobuf:protobuf-java:3.25.1")
    // 2. Runtime libraries for your app
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.26.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}