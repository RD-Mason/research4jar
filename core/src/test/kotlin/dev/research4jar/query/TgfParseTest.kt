package dev.research4jar.query

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the TGF parser (`mvn dependency:tree -DoutputType=tgf`) semantics the
 * Go implementation established: coordinate arities, first-parent paths,
 * direct/depth flags, and exact error messages.
 */
class TgfParseTest {
    private val tree = """
        1 com.example:app:jar:1.0
        2 com.example:api:jar:1.0:compile
        3 com.example:impl:jar:1.0:runtime
        4 com.example:native:jar:linux-x86_64:2.0:runtime
        #
        1 2
        2 3
        3 4
    """.trimIndent()

    @Test
    fun `parses arities, paths, depth, and direct flags`() {
        val graph = DepGraphCapture.parseTGF(StringReader(tree))
        assertEquals("maven", graph.buildTool)
        assertEquals(4, graph.artifacts.size)

        val byCoordinate = graph.artifacts.associateBy { it.coordinate }
        val root = byCoordinate.getValue("com.example:app:1.0")
        assertEquals(0, root.depth)
        assertEquals(emptyList(), root.path)
        assertEquals(false, root.direct)

        val api = byCoordinate.getValue("com.example:api:1.0")
        assertEquals("compile", api.scope)
        assertEquals(true, api.direct)
        assertEquals(1, api.depth)
        assertEquals(listOf("com.example:api:1.0"), api.path)
        assertEquals("com.example:app:1.0", api.parent)

        val impl = byCoordinate.getValue("com.example:impl:1.0")
        assertEquals(false, impl.direct)
        assertEquals(2, impl.depth)
        assertEquals(listOf("com.example:api:1.0", "com.example:impl:1.0"), impl.path)

        // 6-part coordinate: group:artifact:type:classifier:version:scope.
        val native = byCoordinate.getValue("com.example:native:2.0")
        assertEquals("linux-x86_64", native.classifier)
        assertEquals("runtime", native.scope)
        assertEquals(3, native.depth)

        // Artifacts sort by (depth, coordinate).
        assertEquals(
            graph.artifacts.sortedWith(compareBy({ it.depth }, { it.coordinate })),
            graph.artifacts,
        )
    }

    @Test
    fun `malformed node reports the line and Go-quoted text`() {
        val exception = assertFailsWith<RuntimeException> {
            DepGraphCapture.parseTGF(StringReader("junk\n#\n"))
        }
        assertEquals("line 1: malformed TGF node \"junk\"", exception.message)
    }

    @Test
    fun `malformed edge reports the line`() {
        val exception = assertFailsWith<RuntimeException> {
            DepGraphCapture.parseTGF(StringReader("1 com.example:app:jar:1.0\n#\nlonely\n"))
        }
        assertEquals("line 3: malformed TGF edge \"lonely\"", exception.message)
    }

    @Test
    fun `non-coordinate node text is rejected with the offending value`() {
        val exception = assertFailsWith<RuntimeException> {
            DepGraphCapture.parseTGF(StringReader("1 not-a-coordinate\n#\n"))
        }
        assertTrue(
            exception.message!!.startsWith("line 1: "),
            "message was: ${exception.message}",
        )
        assertTrue(exception.message!!.contains("\"not-a-coordinate\""))
    }

    @Test
    fun `empty tree is an error`() {
        val exception = assertFailsWith<RuntimeException> {
            DepGraphCapture.parseTGF(StringReader(""))
        }
        assertEquals("maven dependency tree was empty", exception.message)
    }

    @Test
    fun `tgf sections merge dedupes by shallowest coordinate`() {
        val coreTree = "1 com.fixture:core:jar:1.0\n2 org.demo:lib:jar:2.0:compile\n#\n1 2\n"
        val appTree = "9 com.fixture:app:jar:1.0\n8 com.fixture:core:jar:1.0:compile\n" +
            "7 org.demo:lib:jar:2.0:compile\n#\n9 8\n8 7\n"
        val graph = DepGraphCapture.graphFromTgfSections(listOf(coreTree, appTree), "/root")

        val byCoordinate = graph.artifacts.associateBy { it.coordinate }
        assertEquals(3, graph.artifacts.size)
        // Both module coordinates stay depth-0 roots even though app lists core.
        assertEquals(0, byCoordinate.getValue("com.fixture:core:1.0").depth)
        assertEquals(0, byCoordinate.getValue("com.fixture:app:1.0").depth)
        // The shared external keeps its shortest path (depth 1 via core).
        assertEquals(1, byCoordinate.getValue("org.demo:lib:2.0").depth)
        assertEquals("/root", graph.projectRoot)
        assertEquals("maven", graph.buildTool)
    }
}
