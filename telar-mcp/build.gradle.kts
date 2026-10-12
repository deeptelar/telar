plugins {
    id("telar.kmp-library")
    id("telar.publishing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":telar-agent"))
            // The official Kotlin SDK of the Model Context Protocol. The app connects with it.
            api(libs.mcp.client)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // A transport over channels, so that a test talks to a server in the same process.
            implementation(libs.mcp.testing)
            implementation(project(":telar-test"))
        }
    }
}
