plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.hyundai_test"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.hyundai_test"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".pleosdemo"
        }
        release {
            // Demo signing lets reviewers install the prototype on the emulator.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    sourceSets.getByName("release") {
        res.srcDir("src/debug/res")
        assets.srcDir("src/debug/assets")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    useLibrary("android.car")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.pleos.vehicle)
    releaseImplementation(libs.pleos.vehicle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
