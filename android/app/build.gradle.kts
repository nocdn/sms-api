plugins {
    alias(libs.plugins.android.application)
}

val baseVersionCode = 1
val baseVersionName = "1.0"
val ciVersionCode = providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull()
val ciVersionSuffix = providers.gradleProperty("ciVersionSuffix").orNull

android {
    namespace = "com.example.smsapi"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.smsapi"
        minSdk = 23
        targetSdk = 36
        versionCode = ciVersionCode ?: baseVersionCode
        versionName = ciVersionSuffix?.let { "$baseVersionName-$it" } ?: baseVersionName

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
}
