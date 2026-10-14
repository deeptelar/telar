plugins {
    id("telar.jvm-library")
    id("telar.publishing")
}

dependencies {
    api(project(":telar-core"))
    api(libs.opentelemetry.api)
    // Makes the span of a node current in its coroutine, on whichever thread the node runs.
    implementation(libs.opentelemetry.extension.kotlin)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}
