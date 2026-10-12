plugins {
    id("telar.kmp-library")
    id("telar.publishing")
    // For the @Serializable classes of the tests. The module itself has none.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":telar-agent"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
