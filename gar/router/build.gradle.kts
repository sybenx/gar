import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GAR: pure Kotlin/JVM module (no Android) so its logic is unit-testable on the JVM. See gar/DESIGN.md.
plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Eval tooling (LlamaServerEngine, EvalRunner, GoldenCapture) runs on a dev machine against
// llama-server. It uses java.net.http, which Android lacks, so it is not part of `main`.
val evalSourceSet: SourceSet = sourceSets.create("eval") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[evalSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get(), configurations.api.get())
configurations[evalSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // keep the eval tooling compiling even though nothing in the build runs it
    dependsOn(evalSourceSet.classesTaskName)
}

dependencies {
    api(libs.dicio.numbers)
    api(libs.gar.kotlinx.coroutines.core)
    implementation(libs.kotlin.serialization)
    implementation(libs.gar.snakeyaml.engine)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.gar.kotlinx.coroutines.test)
}

fun evalProperty(name: String, default: String): String =
    (findProperty("gar.eval.$name") as String?) ?: default

// ./gradlew :gar:router:eval -Pgar.eval.server=http://127.0.0.1:18950 -Pgar.eval.format=hermes
// (gar/eval/run-eval.sh starts and stops llama-server around this)
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Runs gar/eval/cases.yml through LlmRouter against a running llama-server"
    classpath = evalSourceSet.runtimeClasspath
    mainClass.set("cc.aaro.gar.router.eval.EvalRunnerKt")
    workingDir = rootDir
    args = listOf(
        "--server", evalProperty("server", "http://127.0.0.1:18950"),
        "--format", evalProperty("format", "hermes"),
        "--model", evalProperty("model", "model"),
        "--cases", evalProperty("cases", "gar/eval/cases.yml"),
        "--tools", evalProperty("tools", "app/src/main/gar/tools.yml"),
        "--definitions", evalProperty("definitions", "app/src/main/sentences/skill_definitions.yml"),
        "--out", evalProperty("out", "gar/router/build/eval"),
    ) + mapOf(
        // prompt experiments on the no_match pseudo-tool:
        // -Pgar.eval.noMatchName=... -Pgar.eval.noMatchDescription=...
        "noMatchName" to "--no-match-name",
        "noMatchDescription" to "--no-match-description",
        // -Pgar.eval.guards=keywords,slots|none, -Pgar.eval.timeoutSeconds=120
        "guards" to "--guards",
        "timeoutSeconds" to "--timeout-seconds",
        // -Pgar.eval.replay=<report.json>: score an earlier run's outputs again, no server needed
        "replay" to "--replay",
    ).flatMap { (property, flag) ->
        (findProperty("gar.eval.$property") as String?)?.let { listOf(flag, it) } ?: listOf()
    }
}

// ./gradlew -q :gar:router:spokenForm -Pgar.spokenForm.in=<lines> -Pgar.spokenForm.out=<spoken lines>
// One line in, one line out through SpokenForm (gar/eval/stt/make-whisper-cases.sh runs it on
// Whisper's transcripts). Relative paths are from the repository root.
tasks.register<JavaExec>("spokenForm") {
    group = "verification"
    description = "Turns each line of a file into SpokenForm's spoken form"
    classpath = evalSourceSet.runtimeClasspath
    mainClass.set("cc.aaro.gar.router.eval.SpokenFormLinesKt")
    workingDir = rootDir
    args = listOf("in", "out").flatMap { name ->
        (findProperty("gar.spokenForm.$name") as String?)?.let { listOf("--$name", it) } ?: listOf()
    }
}

// Re-captures the prompt goldens under src/test/resources/golden from two running llama-servers
// (Qwen3 for hermes, FunctionGemma for functiongemma) and checks every grammar with llama.cpp.
tasks.register<JavaExec>("captureGoldens") {
    group = "verification"
    description = "Captures prompt goldens via llama-server /apply-template"
    classpath = evalSourceSet.runtimeClasspath
    mainClass.set("cc.aaro.gar.router.eval.GoldenCaptureKt")
    workingDir = projectDir
    args = listOf(
        "--hermes", evalProperty("hermesServer", "http://127.0.0.1:18931"),
        "--functiongemma", evalProperty("functionGemmaServer", "http://127.0.0.1:18932"),
        "--dir", "src/test/resources/golden",
    )
}
