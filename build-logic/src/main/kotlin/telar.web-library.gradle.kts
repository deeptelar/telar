import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Conventions for library modules that only work in a browser: Kotlin/JS and Kotlin/Wasm.
 *
 * Their tests need browser APIs, so they run in headless Chrome. Kover measures the JVM only, so
 * these modules have no coverage gate.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("telar.quality")
}

kotlin {
    explicitApi()

    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    // Tests use experimental API freely; the main code opts in where it uses it.
    sourceSets.configureEach {
        if (name.endsWith("Test")) languageSettings.optIn(EXPERIMENTAL_TELAR_API)
    }
}
