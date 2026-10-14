import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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
        // The driver of the browser, with the worker of SQLDelight and the SQLite of sql.js, as an
        // app sets them up.
        webTest.dependencies {
            implementation(libs.sqldelight.web.worker.driver)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(npm("sql.js", libs.versions.sqljs.get()))
        }
        wasmJsTest.dependencies {
            // The Worker of the DOM, which the driver takes.
            implementation(libs.kotlinx.browser)
        }
    }

    // The tests of JS and Wasm run in a browser, because the driver needs a web worker. Node.js has none.
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

    // The tests on Apple targets run on the SQLite of the system, as an app does.
    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach { binaries.configureEach { linkerOpts("-lsqlite3") } }
}

// Node.js has no web worker, and the tests of JS and Wasm run in a browser instead.
tasks.named("jsNodeTest") { enabled = false }
tasks.named("wasmJsNodeTest") { enabled = false }
