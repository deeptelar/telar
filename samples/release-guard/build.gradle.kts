/*
 * Release Guard: audits the repository before a tag is published, pauses for a person, and resumes
 * in a later process. Not published. Run it with:
 *   ./gradlew :samples:release-guard:run                  # audits this repository, saves the run, exits
 *   ./gradlew :samples:release-guard:run --args=approve   # a new process continues the saved run
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":telar-core"))
    implementation(project(":telar-serialization"))
    implementation(project(":telar-checkpoint-file"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.deeptelar.telar.samples.releaseguard.MainKt")
}

tasks.named<JavaExec>("run") {
    // The repository to audit is the clone this sample lives in, and the saved run goes to this
    // project's build directory, so the guard works the same from any directory.
    systemProperty("releaseGuard.repository", rootDir.absolutePath)
    systemProperty(
        "releaseGuard.home",
        layout.buildDirectory
            .dir("release-guard")
            .get()
            .asFile.absolutePath,
    )
}

tasks.test {
    useJUnitPlatform()
}
