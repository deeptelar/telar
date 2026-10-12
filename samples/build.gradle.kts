/*
 * Runnable examples. Not published. Run one with, for example:
 *   ./gradlew :samples:runQuickStart
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
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
    implementation(project(":telar-typesafe"))
    implementation(project(":telar-langchain4j"))
    implementation(project(":telar-agent"))
    implementation(project(":telar-anthropic"))
    implementation(project(":telar-openai"))
    // The Ktor engine that AnthropicChatModel and OpenAiChatModel send their requests with.
    implementation(libs.ktor.client.cio)

    testImplementation(project(":telar-test"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

listOf(
    "QuickStart",
    "DecisionRouter",
    "HumanInTheLoop",
    "ReviewLoop",
    "AskFromANode",
    "Subgraph",
    "ParallelResearch",
    "ChatAgent",
    "ToolAgent",
).forEach { sample ->
    tasks.register<JavaExec>("run$sample") {
        group = "samples"
        description = "Runs the $sample sample."
        mainClass.set("dev.deeptelar.telar.samples.${sample}Kt")
        classpath = sourceSets.main.get().runtimeClasspath
        standardInput = System.`in`
    }
}

// The kitchen: one shift of a pizzeria whose chef is a model, with the points and the tokens it took.
tasks.register<JavaExec>("runKitchenRush") {
    group = "samples"
    description = "Runs the KitchenRush sample."
    mainClass.set("dev.deeptelar.telar.samples.kitchen.KitchenRushKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

// The levels of the tutorial in docs/tutorial/, for example: ./gradlew :samples:runLevel1
(1..8).forEach { level ->
    tasks.register<JavaExec>("runLevel$level") {
        group = "tutorial"
        description = "Runs level $level of the tutorial in docs/tutorial/."
        mainClass.set("dev.deeptelar.telar.samples.tutorial.level$level.Level${level}Kt")
        classpath = sourceSets.main.get().runtimeClasspath
        standardInput = System.`in`
    }
}
