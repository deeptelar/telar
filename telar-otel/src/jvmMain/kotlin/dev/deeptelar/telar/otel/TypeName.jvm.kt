package dev.deeptelar.telar.otel

// The name the OpenTelemetry API of Java records as well.
internal actual fun Throwable.typeName(): String = this::class.java.name
