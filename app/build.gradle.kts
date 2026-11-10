import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.stypox.dicio.unicodeCldrPlugin.UnicodeCldrLanguagesTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.dicio.sentences.compiler.plugin)
        classpath(libs.dicio.unicode.cldr.plugin)
    }
}

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.compose)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.parcelize)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.com.google.protobuf)
    alias(libs.plugins.dicio.sentences.compiler.plugin)
    alias(libs.plugins.dicio.unicode.cldr.plugin)
    alias(libs.plugins.gar.androidx.baselineprofile) // GAR: gar/DESIGN.md, "Frame rate"
}

android {
    namespace = "org.stypox.dicio"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // GAR: the NDK :gar:llm builds with, so AGP can strip libgar_llama.so (51.7 MB -> 4.4 MB)
    ndkVersion = libs.versions.garNdk.get()

    defaultConfig {
        applicationId = "cc.aaro.gar" // GAR: own package id so it installs next to Dicio
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // GAR: own versions from 1.0.0 (Dicio's last was code 19, name 4.2); a release takes the
        // version from its tag (garVersionName())
        val garVersion = garVersionName()
        versionCode = garVersionCode(garVersion)
        versionName = garVersion
        testInstrumentationRunner = "org.stypox.dicio.CustomTestRunner"

        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += arrayOf("arm64-v8a") // GAR: llama.cpp is built for arm64 only
        }
    }

    // GAR: release signing from the environment (CI) or Gradle properties (~/.gradle/
    // gradle.properties); without a keystore the release build stays unsigned
    signingConfigs {
        create("release") {
            val store = garSigningValue("garKeystoreFile", "GAR_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = garSigningValue("garKeystorePassword", "GAR_KEYSTORE_PASSWORD")
                keyAlias = garSigningValue("garKeyAlias", "GAR_KEY_ALIAS") ?: "gar"
                keyPassword = garSigningValue("garKeyPassword", "GAR_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            // GAR: -PgarIdSuffix=gar builds a worktree branch as the installed debug app
            var normalizedGitBranch = (providers.gradleProperty("garIdSuffix").orNull ?: gitBranch()).replaceFirst("^[^A-Za-z]+", "").replace(Regex("[^0-9A-Za-z]+"), "")
            applicationIdSuffix = ".$normalizedGitBranch"
            // GAR: the branch stays out of what the user sees: the version ends in "-debug" (Dicio:
            // the branch), and the name is strings.xml's "GAR" (Dicio set app_name to
            // "Dicio-<branch>" here, which made "GAR-gar")
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (garSigningValue("garKeystoreFile", "GAR_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release") // GAR
            }
        }
        // GAR: release code with the baseline profile, signed with the debug key and profileable
        // (src/benchmark/AndroidManifest.xml), for measuring and maybe for the phone: debug
        // builds ignore baseline profiles (gar/DESIGN.md, "Frame rate")
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
        }
    }

    // GAR: the benchmark build type uses the profile generated for release
    sourceSets.getByName("benchmark").baselineProfiles.srcDir("src/release/generated/baselineProfiles")

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
            // Just to opt in to future behavior and fix a warning; remove once the future behavior
            // becomes the default behavior. This is about @Annotations on data class fields.
            freeCompilerArgs = listOf("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        generateProtoTasks {
            all().forEach {
                it.builtins {
                    create("kotlin") {
                        option("lite")
                    }
                    create("java") {
                        option("lite")
                    }
                }
            }
        }
    }
}

// GAR: the baseline profile plugin's copies of release (nonMinifiedRelease, benchmarkRelease)
// install on the emulator with the debug key; this runs after the plugin made them
androidComponents {
    finalizeDsl { extension ->
        for (name in listOf("nonMinifiedRelease", "benchmarkRelease")) {
            extension.buildTypes.findByName(name)?.signingConfig =
                extension.signingConfigs.getByName("debug")
        }
    }
}

// workaround for https://github.com/google/ksp/issues/1590
// remove when not needed anymore
val kspKotlinRegex = "^ksp(.*)Kotlin$".toRegex()
androidComponents {
    onVariants(selector().all()) { variant ->
        afterEvaluate {
            tasks.named(kspKotlinRegex::matches).configureEach {
                val capName = kspKotlinRegex.find(name)!!.groupValues[1]
                dependsOn(tasks.named("generate${capName}Proto"))
            }
        }
    }
}

// GAR: ship the tool catalog and Dicio's skill definitions as assets (assets/gar/), the LLM
// router builds its tool schemas from them at runtime
abstract class GarCopyCatalogTask : DefaultTask() {
    @get:InputFiles
    abstract val catalogFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("gar")
        out.deleteRecursively()
        out.mkdirs()
        catalogFiles.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}
val garCopyCatalog = tasks.register<GarCopyCatalogTask>("garCopyCatalog") {
    catalogFiles.from("src/main/gar/tools.yml", "src/main/sentences/skill_definitions.yml")
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(garCopyCatalog, GarCopyCatalogTask::outputDir)
    }
}

tasks.withType(UnicodeCldrLanguagesTask::class) {
    // tell the UnicodeCldrLanguagesTask plugin which git commit of the
    // https://github.com/unicode-org/cldr repo to use as a source of data
    unicodeCldrGitCommit = libs.versions.unicodeCldrGitCommit
}

dependencies {
    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Dicio own libraries
    implementation(libs.dicio.numbers)
    implementation(project(":skill"))

    // GAR modules (see gar/DESIGN.md)
    implementation(project(":gar:router"))
    implementation(project(":gar:llm"))
    implementation(project(":gar:assist"))
    implementation(project(":gar:stt"))
    implementation(libs.gar.work.runtime.ktx) // GAR: the song skill's offline queue
    implementation(libs.gar.metrics.performance) // GAR: JankStats
    implementation(libs.gar.profileinstaller) // GAR: installs the baseline profile
    baselineProfile(project(":gar:baselineprofile")) // GAR: generates it

    // Android
    implementation(libs.appcompat)

    // Compose (check out https://developer.android.com/jetpack/compose/bom/bom-mapping)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.test.android.compose.ui.test.junit4)
    debugImplementation(libs.debug.compose.ui.tooling)
    debugImplementation(libs.debug.compose.ui.test.manifest)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)
    androidTestImplementation(libs.hilt.android.testing)
    //androidTestAnnotationProcessor(libs.hilt.android.compiler)
    testImplementation(libs.hilt.android.testing)
    testAnnotationProcessor(libs.hilt.android.compiler)

    // Protobuf and Datastore
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.protobuf.java.lite)
    implementation(libs.datastore)

    // Navigation
    implementation(libs.kotlin.serialization)
    implementation(libs.navigation)

    // Vosk
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.vosk.android)

    // LiteRT / Tensorflow Lite
    implementation(libs.litert)

    // OkHttp
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.accompanist.drawablepainter)

    // Permission Flow https://github.com/PatilShreyas/permission-flow-android
    implementation(libs.permission.flow.android)
    implementation(libs.permission.flow.compose)

    // Miscellaneous
    implementation(libs.unbescape)
    implementation(libs.jsoup)

    // Used by skills
    implementation(libs.exp4j)

    // Testing
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.gar.kotlinx.coroutines.test) // GAR
    testImplementation(libs.gar.snakeyaml.engine) // GAR: catalog test reads the YAML files directly
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.ui.automator)
}

// this is required to avoid NoClassDefFoundError for ActivityInvoker during androidTest
// https://github.com/android/android-test/issues/2247#issuecomment-2194435444
configurations.configureEach {
    resolutionStrategy {
        force(libs.test.core)
    }
}

fun gitBranch(): String {
    // GAR: FileRepositoryBuilder also follows the .git file of a linked worktree, Git.open doesn't
    return org.eclipse.jgit.storage.file.FileRepositoryBuilder().findGitDir(rootDir).build().use { it.branch }
}

// GAR: Gradle property first, then the environment variable
fun garSigningValue(property: String, env: String): String? =
    providers.gradleProperty(property).orNull ?: System.getenv(env)

// GAR: -PgarVersion=1.2.3 or GAR_VERSION=1.2.3 (the release workflow sets it from the tag v1.2.3)
fun garVersionName(): String =
    providers.gradleProperty("garVersion").orNull ?: System.getenv("GAR_VERSION") ?: "1.0.0"

// GAR: 1.2.3 -> 10203, so every later version gets a higher code (minor and patch below 100)
fun garVersionCode(versionName: String): Int {
    val parts = versionName.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "GAR version must be x.y.z, got $versionName" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}
