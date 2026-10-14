import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Conventions for Kotlin Multiplatform library modules.
 *
 * The JVM artifact also serves Android. Apple targets can only be tested on macOS hosts;
 * CI runs them on a macOS runner.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("telar.quality")
}

/*
 * How long one test may take on Node.js. Mocha's default of 2 seconds is too short for the first test
 * of a module on a slow CI runner, which also loads the libraries of the module: the first test of
 * telar-mcp took 3 seconds on macOS and failed. `runTest` keeps its own limit inside.
 */
val NODE_TEST_TIMEOUT = "30s"

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        nodejs {
            testTask { useMocha { timeout = NODE_TEST_TIMEOUT } }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask { useMocha { timeout = NODE_TEST_TIMEOUT } }
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
