package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

// As the setup of SQLDelight for the browser has it: webpack bundles the worker from the URL.
actual fun workerDriver(): SqlDriver =
    WebWorkerDriver(Worker(js("""new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url)""")))
