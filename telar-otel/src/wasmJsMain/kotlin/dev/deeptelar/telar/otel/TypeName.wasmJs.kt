package dev.deeptelar.telar.otel

internal actual fun Throwable.typeName(): String = this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"
