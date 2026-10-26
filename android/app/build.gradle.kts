plugins {
    id("com.android.application")
}

android {
    namespace = "dev.standby"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.standby"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/standby.jks")
            storePassword = "standby"
            keyAlias = "standby"
            keyPassword = "standby"
        }
    }

    buildTypes {
        debug {
            // same key as release, so either build installs over the other
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
