package dev.deeptelar.telar

/**
 * Marks Telar API that may still change after `1.0`.
 *
 * A marked declaration can change its name, its parameters or its behavior, or be removed, in a
 * minor release, without the deprecation cycle of stable API. The changelog says each change. A
 * patch release does not change it. When it is stable, the mark is removed, and the changelog says
 * so.
 *
 * Opt in where you use it with `@OptIn(ExperimentalTelarApi::class)`, or for a whole module with
 * `optIn.add("dev.deeptelar.telar.ExperimentalTelarApi")` in the compiler options of Kotlin.
 *
 * Kotlin does not allow the mark on a parameter: a new parameter that may still change comes with
 * a marked overload.
 */
@RequiresOptIn(
    message = "This API of Telar may change in a minor release.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalTelarApi
