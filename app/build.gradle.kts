import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    versionFile.inputStream().use { load(it) }
}

val configuredVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
    ?: versionProperties.getProperty("VERSION_CODE").toInt()
val configuredVersionName = providers.gradleProperty("appVersionName").orNull
    ?: versionProperties.getProperty("VERSION_NAME")

android {
    namespace = "com.zephyr.qr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zephyr.qr"
        minSdk = 34
        targetSdk = 34
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("managed") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
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
        buildConfig = true
        viewBinding = true
    }

    lint {
        // These stay pinned intentionally until Meta's passthrough-camera guidance and
        // the project compileSdk move beyond API 34.
        disable += setOf("GradleDependency", "ObsoleteSdkInt", "OldTargetApi")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
