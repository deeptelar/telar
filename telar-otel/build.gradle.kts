import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("telar.kmp-library")
    id("telar.publishing")
}

kotlin {
    sourceSets {
        // OtlpObserver: the spans are built here and sent as OTLP over the HttpClient of the app, so
        // that tracing works on every platform, the browser included.
        commonMain.dependencies {
            api(project(":telar-core"))
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // OpenTelemetryObserver: the OpenTelemetry API of Java, for apps that already have its SDK or agent.
        jvmMain.dependencies {
            api(libs.opentelemetry.api)
            // Makes the span of a node current in its coroutine, on whichever thread the node runs.
            implementation(libs.opentelemetry.extension.kotlin)
        }
        jvmTest.dependencies {
            implementation(libs.opentelemetry.sdk.testing)
        }
        // The engine of Ktor that apps in a browser use, which sends with fetch.
        webTest.dependencies {
            implementation(libs.ktor.client.js)
        }
    }

    // The tests of JS and Wasm also run in a browser, where an app sends its spans with fetch.
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
}
