plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.orca.autoreply"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.orca.autoreply"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
}

// Copy the debug APK to the project root after every assembleDebug.
val copyApkToRoot by tasks.registering {
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    val rootDir = rootProject.layout.projectDirectory
    doLast {
        apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.forEach {
            it.copyTo(rootDir.file(it.name).asFile, overwrite = true)
        }
    }
}
tasks.configureEach {
    if (name == "assembleDebug") finalizedBy(copyApkToRoot)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
