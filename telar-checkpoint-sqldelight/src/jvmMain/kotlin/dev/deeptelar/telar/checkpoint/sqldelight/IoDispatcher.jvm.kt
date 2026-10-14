package dev.deeptelar.telar.checkpoint.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
