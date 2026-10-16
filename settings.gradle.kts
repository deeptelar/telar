pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "telar"

include("telar-core")
include("telar-serialization")
include("telar-checkpoint-file")
include("telar-checkpoint-browser")
include("telar-checkpoint-sqldelight")
include("telar-agent")
include("telar-anthropic")
include("telar-openai")
include("telar-langchain4j")
include("telar-typesafe")
include("telar-test")
include("telar-mcp")
include("telar-otel")
include("samples")
include("samples:release-guard")
