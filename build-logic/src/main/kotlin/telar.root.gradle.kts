/*
 * Root-project conventions: aggregated API docs and coverage reports, and
 * binary-compatibility validation for every published module.
 */
plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

dokka {
    pluginsConfiguration.html {
        // Dokka shows a custom asset named logo-icon.svg in the page header instead of its own logo.
        customAssets.from(layout.projectDirectory.file("docs/brand/dokka/logo-icon.svg"))
        // A custom style sheet named logo-styles.css sets the size of that logo.
        customStyleSheets.from(layout.projectDirectory.file("docs/brand/dokka/logo-styles.css"))
    }
}

apiValidation {
    // Samples are not published, so they have no API to keep stable.
    ignoredProjects.add("samples")
    ignoredProjects.add("release-guard")
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
