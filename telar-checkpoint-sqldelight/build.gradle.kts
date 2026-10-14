import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("telar.kmp-library")
    id("telar.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":telar-core"))
            api(libs.sqldelight.runtime)
            implementation(project(":telar-serialization"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        // The driver of iOS and macOS. Linux and Windows have one too, but their SQLite is not part
        // of the system that Kotlin/Native links against.
        appleTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }

    // The tests on Apple targets run on the SQLite of the system, as an app does.
    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach { binaries.configureEach { linkerOpts("-lsqlite3") } }
}
