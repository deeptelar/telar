package dev.deeptelar.telar.samples.releaseguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What a command printed, and whether it succeeded. */
data class Output(
    val exitCode: Int,
    val text: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * The repository being audited. Every call only reads: the guard never changes the repository, it
 * prints the commands that publish the tag.
 */
class Repository(
    val directory: File,
) {
    /** Runs `git` in [directory]. A missing `git` is an exit code of -1, not an exception. */
    suspend fun git(vararg args: String): Output =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(listOf("git", *args))
                        .directory(directory)
                        .redirectErrorStream(true)
                        .start()
                val text =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                Output(process.waitFor(), text)
            } catch (e: IOException) {
                Output(-1, "git could not be started: ${e.message}")
            }
        }

    /** The text of a file of the repository, or `null` when it does not exist. */
    suspend fun read(path: String): String? =
        withContext(Dispatchers.IO) {
            File(directory, path).takeIf { it.isFile }?.readText()
        }
}

/** The value of [key] in the text of a `.properties` file. */
fun propertyOf(properties: String, key: String): String? =
    properties
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()

/** The next tag after [tag]: the last number goes up by one, so `v0.1.0-alpha10` becomes `v0.1.0-alpha11`. */
fun nextTag(tag: String): String {
    val last = Regex("""\d+(?!.*\d)""").find(tag) ?: return "$tag.1"
    return tag.replaceRange(last.range, (last.value.toInt() + 1).toString())
}

/** The lines of the section `## [title]` of a changelog, without blank lines, or `null` when there is no such section. */
fun changelogSection(changelog: String, title: String): List<String>? {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.startsWith("## [$title]") }
    if (start < 0) return null
    return lines
        .drop(start + 1)
        .takeWhile { !it.startsWith("## ") }
        .filter { it.isNotBlank() }
}
