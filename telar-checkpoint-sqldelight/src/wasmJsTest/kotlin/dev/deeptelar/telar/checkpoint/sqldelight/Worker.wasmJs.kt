@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker
import kotlin.js.ExperimentalWasmJsInterop

actual fun workerDriver(): SqlDriver = WebWorkerDriver(sqlJsWorker())

// As the setup of SQLDelight for the browser has it: webpack bundles the worker from the URL.
private fun sqlJsWorker(): Worker = js("""new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url))""")
