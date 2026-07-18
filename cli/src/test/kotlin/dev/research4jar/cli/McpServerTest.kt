package dev.research4jar.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpServerTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `initialize only advertises the supported protocol and cli version`() {
        val unsupported = request(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"9999-99-99"}}""",
        ).single()
        assertEquals("2024-11-05", unsupported["result"]["protocolVersion"].asText())
        assertEquals(CLI_VERSION, unsupported["result"]["serverInfo"]["version"].asText())

        val supported = request(
            """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
        ).single()
        assertEquals("2024-11-05", supported["result"]["protocolVersion"].asText())
    }

    @Test
    fun `tool schemas expose implemented context and freshness options only`() {
        val response = request(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
        ).single()
        val tools = response["result"]["tools"].associateBy { it["name"].asText() }

        val indexProperties = assertNotNull(tools["index_project"])["inputSchema"]["properties"]
        assertTrue(indexProperties.has("home"))
        assertTrue(!indexProperties.has("indexer"), "the in-process index tool must not advertise a no-op")
        assertTrue(indexProperties.has("registry"))
        assertTrue(indexProperties.has("registry_pubkey"))

        val statusProperties = assertNotNull(tools["project_status"])["inputSchema"]["properties"]
        assertTrue(statusProperties.has("check_classpath"))

        val queryTools = setOf(
            "find_config_properties", "find_implementations", "find_by_annotation",
            "get_class", "get_bean_definitions", "explain_conditional", "find_string",
            "list_extension_points", "find_class", "find_method", "list_packages",
            "search_symbols", "open_symbol", "dependency_precise", "class_origin",
            "find_artifact", "why_dependency",
        )
        for (name in queryTools) {
            val schema = assertNotNull(tools[name], name)["inputSchema"]
            assertTrue(!schema["additionalProperties"].asBoolean(), "$name must reject unknown arguments")
            val properties = schema["properties"]
            assertTrue(properties.has("home"), "$name must expose the implemented home override")
        }

        val paging = assertNotNull(tools["search_symbols"])["inputSchema"]["properties"]
        assertEquals(1, paging["page"]["minimum"].asInt())
        assertEquals(1_000, paging["page_size"]["maximum"].asInt())
    }

    @Test
    fun `tool calls reject unsafe pagination before opening a project`() {
        val oversized = request(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"find_class","arguments":{"text":"Widget","page_size":2147483647}}}""",
        ).single()
        assertTrue(oversized["result"]["isError"].asBoolean())
        assertTrue(oversized["result"]["content"][0]["text"].asText().contains("between 1 and 1000"))

        val deep = request(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_class","arguments":{"text":"Widget","page":2147483647,"page_size":1000}}}""",
        ).single()
        assertTrue(deep["result"]["isError"].asBoolean())
        assertTrue(deep["result"]["content"][0]["text"].asText().contains("100000-result window"))
    }

    @Test
    fun `tool calls enforce each advertised argument contract before opening a project`() {
        val cases = listOf(
            """{"name":"find_class","arguments":{"text":"Widget","project_dr":"/wrong"}}""" to
                "unknown argument: project_dr",
            """{"name":"get_class","arguments":{"fqn":"example.Widget","page":2}}""" to
                "unknown argument: page",
            """{"name":"find_class","arguments":{"text":42}}""" to
                "text must be a string",
            """{"name":"get_class","arguments":{}}""" to
                "fqn is required",
            """{"name":"missing_tool","arguments":{}}""" to
                "unknown tool: missing_tool",
            """{"name":"find_class","arguments":[]}""" to
                "arguments must be an object",
        )

        for ((index, case) in cases.withIndex()) {
            val response = request(
                """{"jsonrpc":"2.0","id":$index,"method":"tools/call","params":${case.first}}""",
            ).single()
            assertTrue(response["result"]["isError"].asBoolean(), case.first)
            assertTrue(
                response["result"]["content"][0]["text"].asText().contains(case.second),
                case.first,
            )
        }
    }

    private fun request(vararg lines: String) = ByteArrayOutputStream().use { output ->
        McpServer.serve(
            ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray()),
            output,
        )
        output.toString(Charsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map(mapper::readTree)
            .toList()
    }
}
