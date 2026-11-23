# Supported versions and releases

Telar is in alpha. Public APIs can change before 1.0. Check the
[changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) when upgrading a pinned
version, especially if your app depends on an API that was recently added.

## Kotlin, Java and platforms

Telar is built with Kotlin 2.4. The
[version catalog](https://github.com/deeptelar/telar/blob/main/gradle/libs.versions.toml) pins the
exact compiler version. On the JVM, Kotlin 2.3 also works. For `0.1.0-alpha19`, a Java 17 app
compiled and ran a small graph against `telar-core` with Kotlin 2.3.21. Kotlin 2.2.21 rejected
that artifact's 2.4 metadata. Older compiler compatibility depends on the selected module,
its dependencies, and its target. For Kotlin/Native, JS and Wasm, use the Kotlin version
used to build the release.

When the minimum compiler version for apps changes, the release notes name the new minimum
and the affected targets. A patch release after 1.0 keeps the minimum of the preceding patch.

| Modules | Published targets and runtime requirements |
| --- | --- |
| `telar-core`, `telar-serialization`, `telar-agent`, `telar-anthropic`, `telar-openai`, `telar-typesafe`, `telar-mcp`, `telar-test` | JVM (Java 11 or later, also used by Android), Native, JS and Wasm. The JS and Wasm tests exercise Node.js. |
| `telar-checkpoint-file` | Same published targets. JS and Wasm file access requires Node.js. |
| `telar-checkpoint-sqldelight` | Same published targets. Supply a driver for your platform. Its JS and Wasm tests use the browser web-worker driver. |
| `telar-otel` | Same published targets. JS and Wasm are also tested in a browser. The Java SDK observer is JVM-only. |
| `telar-checkpoint-browser` | JS and Wasm in a browser with `localStorage`. |
| `telar-checkpoint-postgres` | JVM (Java 11 or later). Supply a PostgreSQL JDBC driver and `DataSource`. |
| `telar-langchain4j` | JVM (Java 17 or later). |
| `telar-bom` | A bill of materials with no runtime target. |

The shared Native targets are iOS arm64 (device and simulator), macOS arm64, Linux x64 and arm64,
and Windows MinGW x64. A release's published artifacts are the source of truth for available
targets. Building Telar from source requires a JDK 17 or newer.

## Compatibility after 1.0

Telar follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). After 1.0, a patch
release contains backward-compatible fixes. A minor release can add backward-compatible API
and mark an API deprecated. A major release can remove or change stable public API incompatibly.

The deprecation window is at least two subsequent minor releases and at least three
months. Each deprecation names a replacement or migration path, using Kotlin's
`ReplaceWith` where an automatic replacement is suitable. Removal of a stable public
declaration belongs in a major release.

### Experimental API

A declaration marked `@ExperimentalTelarApi` may still change after 1.0. A minor release can
change its name, its parameters or its behavior, or remove it, without the deprecation window of
stable API, and the changelog says each change. A patch release does not change it. When it is
stable, the mark is removed, and the changelog says so.

Using a marked declaration gives a compiler warning until the app opts in, where it uses it:

```kotlin
@OptIn(ExperimentalTelarApi::class)
fun remember() { /* ... */ }
```

or for a whole module, in its `build.gradle.kts`:

```kotlin
kotlin {
    compilerOptions {
        optIn.add("dev.deeptelar.telar.ExperimentalTelarApi")
    }
}
```

Code that relies on an experimental declaration should read the changelog when it upgrades.
Before 1.0, every API may change; the mark says which parts will still be able to change after it.

## Release cadence and security fixes

Telar publishes at most one routine release per month. Security fixes are released sooner
when needed. During alpha, security fixes are released for the latest published version. See the
[security policy](https://github.com/deeptelar/telar/blob/main/SECURITY.md) for private reporting.

See the [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) for migration notes
and [installation](installation.md) for the current artifact coordinates.
