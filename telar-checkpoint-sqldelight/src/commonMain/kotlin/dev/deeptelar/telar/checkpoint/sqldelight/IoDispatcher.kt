package dev.deeptelar.telar.checkpoint.sqldelight

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatcher for the calls of a driver that block: `Dispatchers.IO` where available, `Dispatchers.Default` on JS/Wasm. */
internal expect val ioDispatcher: CoroutineDispatcher
