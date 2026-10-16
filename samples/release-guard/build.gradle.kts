/*
 * Release Guard: audits the repository before a tag is published, pauses for a person, and resumes
 * in a later process. Not published. Run it with:
 *   ./gradlew :samples:release-guard:run                    # audits this repository, saves the run, exits
 *   ./gradlew :samples:release-guard:run --args=--approve   # a new process continues the saved run
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
    implementation(project(":telar-agent"))
    implementation(project(":telar-anthropic"))
    implementation(project(":telar-openai"))
    implementation(project(":telar-mcp"))
    // The server side of the MCP SDK and its transport over channels, for the server that runs in the process.
    implementation(libs.mcp.server)
    implementation(libs.mcp.testing)
    // The Ktor engine that AnthropicChatModel and OpenAiChatModel send their requests with.
    implementation(libs.ktor.client.cio)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.deeptelar.telar.samples.releaseguard.MainKt")
}

tasks.named<JavaExec>("run") {
    // The repository to audit is the clone this sample lives in. The saved runs go to .checkpoints
    // in this directory, so the guard works the same from any directory.
    systemProperty("releaseGuard.repository", rootDir.absolutePath)
    systemProperty("releaseGuard.home", projectDir.absolutePath)
}

tasks.test {
    useJUnitPlatform()
}
