package dev.deeptelar.telar.otel

// Kotlin/JS does not know the package of a class.
internal actual fun Throwable.typeName(): String = this::class.simpleName ?: "Throwable"
