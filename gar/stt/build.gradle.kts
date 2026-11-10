import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GAR: whisper.cpp speech recognition through a thin JNI layer (libgar_whisper, its own static
// ggml), plus the pure Kotlin parts of capture. Modelled on gar/llm. See gar/DESIGN.md.
plugins {
    alias(libs.plugins.com.android.library)
    alias(libs.plugins.org.jetbrains.kotlin.android)
}

android {
    namespace = "cc.aaro.gar.stt"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.garNdk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += arrayOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // debug builds of whisper.cpp are unusably slow, so optimize in every variant
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                targets += "gar_whisper"
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.garCmake.get()
        }
    }

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The host test runs only if these point at a host build of the JNI library and the model
    // files (see host-test/build-host-jni.sh); declared as inputs so changing them reruns it.
    for (name in listOf("GAR_STT_HOST_JNI_LIB", "GAR_STT_TEST_MODEL", "GAR_STT_TEST_VAD")) {
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    // a rebuilt library at the same path reruns it too
    providers.environmentVariable("GAR_STT_HOST_JNI_LIB").orNull?.takeIf { it.isNotEmpty() }?.let {
        inputs.file(it).optional().withPropertyName("hostJniLib")
    }
}

dependencies {
    implementation(libs.gar.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.gar.kotlinx.coroutines.test)
}
