import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GAR: generates :app's baseline profile on the emulator (gar/DESIGN.md, "Frame rate"):
//   ./gradlew :app:generateReleaseBaselineProfile
// writes app/src/release/generated/baselineProfiles/, which release and benchmark builds use.
plugins {
    alias(libs.plugins.gar.com.android.test)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.gar.androidx.baselineprofile)
}

android {
    namespace = "cc.aaro.gar.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
        }
    }
}

// the emulator that is already running (gartest), never a Gradle-managed one
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.gar.test.ext.junit)
    implementation(libs.test.ui.automator)
    implementation(libs.gar.benchmark.macro.junit4)
}
