package app.embeddy.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for M40: user-visible strings must come from strings.xml.
 *
 * Scans the production Kotlin sources for the literal-string patterns that keep
 * regressing (TalkBack contentDescriptions, Toast messages, share-chooser titles).
 * These three shapes are always user-visible, so the rule can be absolute —
 * broader `Text("...")` literals are not scanned because many are data/number
 * formatting rather than translatable copy.
 */
class StringLiteralGuardTest {

    private val sourceRoot: File =
        sequenceOf("src/main/kotlin", "app/src/main/kotlin")
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: error("Cannot locate app sources from ${File(".").absolutePath}")

    /** Line matches that indicate a hardcoded user-visible string. */
    private val forbiddenPatterns = listOf(
        // TalkBack labels: must be null (decorative) or stringResource(...)
        Regex("""contentDescription\s*=[^\n]*""""),
        // Toast copy: must use context.getString(...)
        Regex("""Toast\.makeText\([^)]*""""),
        // Share-sheet titles: must use context.getString(...)
        Regex("""createChooser\([^)]*""""),
    )

    @Test
    fun `no hardcoded user-visible literals in contentDescription, Toast, or chooser titles`() {
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    if (forbiddenPatterns.any { it.containsMatchIn(code) }) {
                        "${file.relativeTo(sourceRoot)}:${index + 1}: ${line.trim()}"
                    } else null
                }
            }
            .toList()

        assertTrue(
            "User-visible literals found (move to strings.xml):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
