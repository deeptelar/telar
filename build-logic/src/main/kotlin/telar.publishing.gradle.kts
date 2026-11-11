import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

/*
 * Maven Central publishing conventions. Module-specific name and description
 * come from the `POM_NAME` / `POM_DESCRIPTION` properties in the module's `gradle.properties`.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

/*
 * A multiplatform module publishes one artifact per target, and with Dokka applied each of them would
 * carry the whole API reference, the same 1 MB or more ten times over. Maven Central limits what an
 * organization publishes in a month, by file count and by size, so these artifacts get an empty
 * javadoc jar, as kotlinx.coroutines does. The API reference is on the documentation site.
 */
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    mavenPublishing {
        configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Sign only when a key is configured (CI release job), so local publishToMavenLocal keeps working.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        // Read with property(): providers.gradleProperty() does not see a module's own gradle.properties,
        // and Maven Central rejects a POM without a name or a description. property() fails if one is missing.
        name.set(property("POM_NAME").toString())
        description.set(property("POM_DESCRIPTION").toString())
        inceptionYear.set("2026")
        url.set("https://github.com/deeptelar/telar")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("hermosotech")
                name.set("hermosotech")
                url.set("https://github.com/Cuento3yLlevo2")
            }
        }
        scm {
            url.set("https://github.com/deeptelar/telar")
            connection.set("scm:git:git://github.com/deeptelar/telar.git")
            developerConnection.set("scm:git:ssh://git@github.com/deeptelar/telar.git")
        }
    }
}
