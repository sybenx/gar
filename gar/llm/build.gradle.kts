import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GAR: llama.cpp through a thin JNI layer. See gar/DESIGN.md.
plugins {
    alias(libs.plugins.com.android.library)
    alias(libs.plugins.org.jetbrains.kotlin.android)
}

android {
    namespace = "cc.aaro.gar.llm"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.garNdk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += arrayOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // debug builds of llama.cpp are unusably slow, so optimize in every variant
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                targets += "gar_llama"
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
    // The host test runs only if these point at a host build of the JNI library and a GGUF
    // model (see host-test/build-host-jni.sh); declared as inputs so changing them reruns it.
    for (name in listOf("GAR_HOST_JNI_LIB", "GAR_TEST_MODEL", "GAR_TEST_MODEL_FG")) {
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    // a rebuilt library at the same path reruns it too
    providers.environmentVariable("GAR_HOST_JNI_LIB").orNull?.takeIf { it.isNotEmpty() }?.let {
        inputs.file(it).optional().withPropertyName("hostJniLib")
    }
}

dependencies {
    api(project(":gar:router"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.serialization)
}
