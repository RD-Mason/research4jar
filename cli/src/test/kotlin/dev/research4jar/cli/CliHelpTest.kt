package dev.research4jar.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliHelpTest {
    @Test
    fun `every command family provides side effect free help`() {
        val commands = listOf(
            arrayOf("index", "--help"),
            arrayOf("doctor", "--help"),
            arrayOf("status", "--help"),
            arrayOf("cache", "--help"),
            arrayOf("cache", "gc", "--help"),
            arrayOf("registry", "--help"),
            arrayOf("registry", "seed", "--help"),
            arrayOf("dep", "--help"),
            arrayOf("dep", "precise", "--help"),
            arrayOf("dep", "why", "--help"),
            arrayOf("artifact", "--help"),
            arrayOf("class", "--help"),
            arrayOf("method", "--help"),
            *QUERY_COMMANDS.sorted().map { arrayOf(it, "--help") }.toTypedArray(),
            arrayOf("mcp", "--help"),
        )

        for (args in commands) {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val code = runCli(args, PrintStream(stdout), PrintStream(stderr))
            assertEquals(0, code, args.joinToString(" "))
            assertTrue(stdout.toString(Charsets.UTF_8).startsWith("Usage:"), args.joinToString(" "))
            assertEquals("", stderr.toString(Charsets.UTF_8), args.joinToString(" "))
        }
    }

    @Test
    fun `mcp rejects unexpected arguments instead of silently discarding them`() {
        val stdout = ByteArrayOutputStream()
        val code = runCli(
            arrayOf("mcp", "unexpected"),
            PrintStream(stdout),
            PrintStream(ByteArrayOutputStream()),
        )
        assertEquals(2, code)
        assertTrue(stdout.toString(Charsets.UTF_8).contains("mcp accepts no arguments"))
    }

    @Test
    fun `query help only advertises options the command consumes`() {
        fun help(command: String): String {
            val stdout = ByteArrayOutputStream()
            assertEquals(
                0,
                runCli(
                    arrayOf(command, "--help"),
                    PrintStream(stdout),
                    PrintStream(ByteArrayOutputStream()),
                ),
            )
            return stdout.toString(Charsets.UTF_8)
        }

        assertTrue("--page <N>" in help("find-class"))
        assertTrue("--direct" in help("find-implementations"))
        assertTrue("--page <N>" !in help("get-class"))
        assertTrue("--direct" !in help("get-class"))
        assertTrue("--page <N>" !in help("open-symbol"))
    }

    @Test
    fun `top level help describes legacy indexer option truthfully`() {
        val stdout = ByteArrayOutputStream()
        assertEquals(
            0,
            runCli(
                arrayOf("--help"),
                PrintStream(stdout),
                PrintStream(ByteArrayOutputStream()),
            ),
        )
        val help = stdout.toString(Charsets.UTF_8)
        assertTrue("Accepted for legacy compatibility; indexing is in-process" in help)
        assertTrue("auto-located otherwise" !in help)
    }

    @Test
    fun `cli rejects pagination that could become an unlimited sqlite query`() {
        fun invoke(vararg args: String): Pair<Int, String> {
            val stdout = ByteArrayOutputStream()
            val code = runCli(
                arrayOf("find-class", "Widget", *args),
                PrintStream(stdout),
                PrintStream(ByteArrayOutputStream()),
            )
            return code to stdout.toString(Charsets.UTF_8)
        }

        val oversized = invoke("--page-size", Int.MAX_VALUE.toString())
        assertEquals(2, oversized.first)
        assertTrue("between 1 and 1000" in oversized.second)

        val deep = invoke("--page", Int.MAX_VALUE.toString(), "--page-size", "1000")
        assertEquals(2, deep.first)
        assertTrue("100000-result window" in deep.second)
    }

    @Test
    fun `commands reject parsed options they do not consume`() {
        fun reject(vararg args: String): String {
            val stdout = ByteArrayOutputStream()
            assertEquals(
                2,
                runCli(
                    arrayOf(*args),
                    PrintStream(stdout),
                    PrintStream(ByteArrayOutputStream()),
                ),
                args.joinToString(" "),
            )
            return stdout.toString(Charsets.UTF_8)
        }

        assertTrue("unknown option: --page" in reject("get-class", "example.Widget", "--page", "2"))
        assertTrue("unknown option: --direct" in reject("open-symbol", "example.Widget", "--direct"))
        assertTrue("unknown option: --no-source-grep" in reject("dep", "why", "g:a", "--no-source-grep"))
        assertTrue("unknown option: --dry-run" in reject("cache", "stats", "--dry-run"))
        assertTrue("unknown option: --in" in reject("get-source", "example.Widget", "--in", "widget.jar"))
        assertTrue("unknown option: --fetch" in reject("find-class", "Widget", "--fetch"))
    }

    @Test
    fun `search-source requires a jar selector before touching the project`() {
        val stdout = ByteArrayOutputStream()
        val code = runCli(
            arrayOf("search-source", "ObjectMapper"),
            PrintStream(stdout),
            PrintStream(ByteArrayOutputStream()),
        )
        assertEquals(2, code)
        assertTrue("requires --in" in stdout.toString(Charsets.UTF_8))
    }
}
