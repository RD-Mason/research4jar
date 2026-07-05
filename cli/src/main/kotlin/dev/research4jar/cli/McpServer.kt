package dev.research4jar.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.envcheck.EnvCheck
import dev.research4jar.query.ProjectIndex
import dev.research4jar.query.ProjectNotFoundException
import dev.research4jar.query.dependencyPrecise
import dev.research4jar.query.classPrecise
import dev.research4jar.query.artifactPrecise
import dev.research4jar.query.explainConditional
import dev.research4jar.query.findByAnnotation
import dev.research4jar.query.findClass
import dev.research4jar.query.findConfigProperties
import dev.research4jar.query.findImplementations
import dev.research4jar.query.findMethod
import dev.research4jar.query.findString
import dev.research4jar.query.getBeanDefinitions
import dev.research4jar.query.getClass
import dev.research4jar.query.listExtensionPoints
import dev.research4jar.query.listPackages
import dev.research4jar.query.openSymbol
import dev.research4jar.query.projectStatus
import dev.research4jar.query.searchSymbol
import dev.research4jar.query.whyDependency
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Minimal Model Context Protocol server over stdio (newline-delimited
 * JSON-RPC 2.0), ported from querier/internal/mcp/server.go. Tool names,
 * schemas, and error strings must stay identical to the Go server.
 */
object McpServer {
    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val SERVER_NAME = "research4jar"
    private const val SERVER_VERSION = "0.2.0"

    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun serve(stdin: InputStream, stdout: OutputStream) {
        val reader: BufferedReader = stdin.bufferedReader()
        val writer = PrintStream(stdout, false, "UTF-8")
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            val reply = handleLine(line.trim()) ?: continue
            writer.println(mapper.writeValueAsString(reply))
            writer.flush()
        }
    }

    private fun handleLine(line: String): Map<String, Any?>? {
        val incoming: JsonNode = try {
            mapper.readTree(line)
        } catch (exception: Exception) {
            return mapOf(
                "jsonrpc" to "2.0",
                "error" to mapOf(
                    "code" to -32700,
                    "message" to "parse error: ${exception.message}",
                ),
            )
        }
        val id = incoming.get("id") ?: return null // notification: no response
        val method = incoming.get("method")?.asText() ?: ""
        val params = incoming.get("params")

        val reply = LinkedHashMap<String, Any?>()
        reply["jsonrpc"] = "2.0"
        reply["id"] = id
        when (method) {
            "initialize" -> reply["result"] = mapOf(
                "protocolVersion" to negotiatedVersion(params),
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
            )

            "ping" -> reply["result"] = emptyMap<String, Any>()
            "tools/list" -> reply["result"] = mapOf("tools" to toolCatalog())
            "tools/call" -> reply["result"] = callTool(params)
            else -> reply["error"] = mapOf(
                "code" to -32601,
                "message" to "method not found: $method",
            )
        }
        return reply
    }

    private fun negotiatedVersion(params: JsonNode?): String {
        val requested = params?.get("protocolVersion")?.asText() ?: ""
        return if (requested.isNotEmpty()) requested else PROTOCOL_VERSION
    }

    data class ToolArguments(
        val projectDir: String = "",
        val home: String = "",
        val jars: String = "",
        val indexer: String = "",
        val registry: String = "",
        val registryPubkey: String = "",
        val prefix: String = "",
        val fqn: String = "",
        val text: String = "",
        val arg: String = "",
        val direct: Boolean = false,
        val noSourceGrep: Boolean = false,
        val page: Int = 1,
        val pageSize: Int = 20,
        val sourceBuild: Boolean = false,
    )

    private fun parseArguments(node: JsonNode?): ToolArguments {
        fun text(name: String) = node?.get(name)?.asText() ?: ""
        fun flag(name: String) = node?.get(name)?.asBoolean() ?: false
        fun int(name: String, fallback: Int): Int {
            val value = node?.get(name)?.asInt() ?: 0
            return if (value < 1) fallback else value
        }
        return ToolArguments(
            projectDir = text("project_dir"),
            home = text("home"),
            jars = text("jars"),
            indexer = text("indexer"),
            registry = text("registry"),
            registryPubkey = text("registry_pubkey"),
            prefix = text("prefix"),
            fqn = text("fqn"),
            text = text("text"),
            arg = text("arg"),
            direct = flag("direct"),
            noSourceGrep = flag("no_source_grep"),
            page = int("page", 1),
            pageSize = int("page_size", 20),
            sourceBuild = flag("source_build"),
        )
    }

    private fun callTool(params: JsonNode?): Map<String, Any?> {
        val name = params?.get("name")?.asText() ?: ""
        val arguments = parseArguments(params?.get("arguments"))
        val result = try {
            dispatchTool(name, arguments)
        } catch (_: ProjectNotFoundException) {
            return toolError(
                "No Research4Jar index found for this project. " +
                    "Run the index_project tool first (it auto-resolves the classpath " +
                    "via Maven/Gradle), or pass project_dir explicitly.",
            )
        } catch (exception: Exception) {
            return toolError(exception.message ?: exception.toString())
        }
        val encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)
        return mapOf("content" to listOf(mapOf("type" to "text", "text" to encoded)))
    }

    private fun dispatchTool(name: String, arguments: ToolArguments): Any {
        if (name == "check_environment") {
            return EnvCheck.run(
                projectDir = arguments.projectDir,
                sourceBuild = arguments.sourceBuild,
            )
        }
        if (name == "index_project") {
            return IndexOrchestrator.runIndexTool(arguments)
        }
        if (name == "project_status") {
            return projectStatus(arguments.projectDir, arguments.home)
        }
        val (pointer, manifestPath) = ProjectIndex.resolve(
            arguments.projectDir.ifEmpty { null },
            arguments.home.ifEmpty { null },
        )
        return when (name) {
            "find_config_properties" -> {
                require(arguments.prefix.isNotEmpty()) { "prefix is required" }
                findConfigProperties(pointer, manifestPath, arguments.prefix, arguments.page, arguments.pageSize)
            }

            "find_implementations" -> {
                require(arguments.fqn.isNotEmpty()) { "fqn is required" }
                findImplementations(pointer, manifestPath, arguments.fqn, arguments.direct, arguments.page, arguments.pageSize)
            }

            "find_by_annotation" -> {
                require(arguments.fqn.isNotEmpty()) { "fqn is required" }
                findByAnnotation(pointer, manifestPath, arguments.fqn, arguments.direct, arguments.page, arguments.pageSize)
            }

            "get_class" -> {
                require(arguments.fqn.isNotEmpty()) { "fqn is required" }
                getClass(pointer, manifestPath, arguments.fqn)
            }

            "get_bean_definitions" -> {
                require(arguments.fqn.isNotEmpty()) { "fqn is required" }
                getBeanDefinitions(pointer, manifestPath, arguments.fqn, arguments.page, arguments.pageSize)
            }

            "explain_conditional" -> {
                require(arguments.fqn.isNotEmpty()) { "fqn is required" }
                explainConditional(pointer, manifestPath, arguments.fqn)
            }

            "find_string" -> {
                require(arguments.text.isNotEmpty()) { "text is required" }
                findString(pointer, manifestPath, arguments.text, arguments.page, arguments.pageSize)
            }

            "list_extension_points" ->
                listExtensionPoints(pointer, manifestPath, arguments.arg, arguments.page, arguments.pageSize)

            "find_class" -> {
                val term = firstNonEmpty(arguments.text, arguments.fqn, arguments.arg)
                require(term.isNotEmpty()) { "text, fqn, or arg is required" }
                findClass(pointer, manifestPath, term, arguments.page, arguments.pageSize)
            }

            "find_method" -> {
                val term = firstNonEmpty(arguments.text, arguments.arg)
                require(term.isNotEmpty()) { "text or arg is required" }
                findMethod(pointer, manifestPath, term, arguments.page, arguments.pageSize)
            }

            "list_packages" ->
                listPackages(pointer, manifestPath, arguments.arg, arguments.page, arguments.pageSize)

            "search_symbols" -> {
                require(arguments.text.isNotEmpty()) { "text is required" }
                searchSymbol(pointer, manifestPath, arguments.text, arguments.page, arguments.pageSize)
            }

            "open_symbol" -> {
                val term = firstNonEmpty(arguments.fqn, arguments.arg)
                require(term.isNotEmpty()) { "fqn or arg is required" }
                openSymbol(pointer, manifestPath, term)
            }

            "why_dependency" -> {
                val term = firstNonEmpty(arguments.fqn, arguments.arg)
                require(term.isNotEmpty()) { "fqn or arg is required" }
                val projectRoot = ProjectIndex.root(arguments.projectDir.ifEmpty { null }).toString()
                whyDependency(pointer, manifestPath, projectRoot, term)
            }

            "dependency_precise" -> {
                val term = firstNonEmpty(arguments.text, arguments.fqn, arguments.arg)
                require(term.isNotEmpty()) { "text, fqn, or arg is required" }
                val projectRoot = ProjectIndex.root(arguments.projectDir.ifEmpty { null }).toString()
                dependencyPrecise(pointer, manifestPath, projectRoot, term, arguments.pageSize, !arguments.noSourceGrep)
            }

            "class_origin" -> {
                val term = firstNonEmpty(arguments.text, arguments.fqn, arguments.arg)
                require(term.isNotEmpty()) { "text, fqn, or arg is required" }
                val projectRoot = ProjectIndex.root(arguments.projectDir.ifEmpty { null }).toString()
                classPrecise(pointer, manifestPath, projectRoot, term, arguments.pageSize, !arguments.noSourceGrep)
            }

            "find_artifact" -> {
                val term = firstNonEmpty(arguments.text, arguments.arg)
                require(term.isNotEmpty()) { "text or arg is required" }
                val projectRoot = ProjectIndex.root(arguments.projectDir.ifEmpty { null }).toString()
                artifactPrecise(pointer, manifestPath, projectRoot, term, arguments.pageSize, !arguments.noSourceGrep)
            }

            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    private fun toolError(message: String): Map<String, Any?> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to message)),
        "isError" to true,
    )

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotEmpty() } ?: ""

    private fun toolCatalog(): List<Map<String, Any?>> {
        val projectDir = mapOf(
            "type" to "string",
            "description" to "Spring project root (directory containing .research4jar). " +
                "Defaults to searching upward from the server's working directory.",
        )
        val paging = mapOf(
            "page" to mapOf("type" to "integer", "description" to "Result page, 1-based (default 1)."),
            "page_size" to mapOf("type" to "integer", "description" to "Results per page (default 20)."),
        )

        fun withPaging(props: Map<String, Any?>): Map<String, Any?> =
            props + paging + mapOf("project_dir" to projectDir)

        fun schema(props: Map<String, Any?>, vararg required: String): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            result["type"] = "object"
            result["properties"] = props
            if (required.isNotEmpty()) result["required"] = required.toList()
            return result
        }

        fun tool(name: String, description: String, schema: Map<String, Any?>): Map<String, Any?> =
            mapOf("name" to name, "description" to description, "inputSchema" to schema)

        return listOf(
            tool(
                "check_environment",
                "List the local tools Research4Jar needs, report missing runtime/build " +
                    "requirements, and include user-facing and agent-executable install guidance.",
                schema(
                    mapOf(
                        "project_dir" to projectDir,
                        "source_build" to mapOf(
                            "type" to "boolean",
                            "description" to "Also check tools needed to build Research4Jar from source.",
                        ),
                    ),
                ),
            ),
            tool(
                "index_project",
                "Index a Spring Boot project's dependency jars into the local fact " +
                    "database. Resolves the runtime classpath automatically via Maven/Gradle " +
                    "when jars is omitted. Run once per project (re-runs are incremental and fast).",
                schema(
                    mapOf(
                        "project_dir" to projectDir,
                        "jars" to mapOf(
                            "type" to "string",
                            "description" to "Optional jar directory, glob, or comma-separated jar list. Omit to auto-resolve via the build tool.",
                        ),
                        "registry" to mapOf(
                            "type" to "string",
                            "description" to "Optional shard registry base URL. When fully covered, indexing finishes without launching the JVM indexer.",
                        ),
                        "registry_pubkey" to mapOf(
                            "type" to "string",
                            "description" to "Optional hex ed25519 public key; downloaded registry shards must have valid signatures.",
                        ),
                    ),
                ),
            ),
            tool(
                "project_status",
                "Report whether the current project has a Research4Jar index and show " +
                    "project pointer, session database, manifest, coverage, and dependency provenance state. " +
                    "Use before dependency/class queries to decide whether index_project is needed.",
                schema(
                    mapOf(
                        "project_dir" to projectDir,
                        "home" to mapOf(
                            "type" to "string",
                            "description" to "Optional Research4Jar data home; defaults to the standard user data directory.",
                        ),
                    ),
                ),
            ),
            tool(
                "find_config_properties",
                "Find Spring configuration properties by prefix (e.g. spring.datasource). " +
                    "Returns name, type, default value, description, and the source jar.",
                schema(
                    withPaging(mapOf("prefix" to mapOf("type" to "string", "description" to "Configuration property prefix or exact name."))),
                    "prefix",
                ),
            ),
            tool(
                "find_implementations",
                "Find classes implementing an interface or extending a class across all " +
                    "indexed jars. Transitive by default (walks subinterfaces and superclass chains); " +
                    "set direct=true for declared-only matches.",
                schema(
                    withPaging(
                        mapOf(
                            "fqn" to mapOf("type" to "string", "description" to "Interface or class fully-qualified name."),
                            "direct" to mapOf("type" to "boolean", "description" to "Only directly declared implements/extends."),
                        ),
                    ),
                    "fqn",
                ),
            ),
            tool(
                "find_by_annotation",
                "Find classes annotated with an annotation across all indexed jars. " +
                    "Expands meta-annotations by default (querying @Component also returns @Service " +
                    "classes); set direct=true for directly-present annotations only.",
                schema(
                    withPaging(
                        mapOf(
                            "fqn" to mapOf("type" to "string", "description" to "Annotation fully-qualified name."),
                            "direct" to mapOf("type" to "boolean", "description" to "Only directly present annotations."),
                        ),
                    ),
                    "fqn",
                ),
            ),
            tool(
                "get_class",
                "Get everything indexed about a class: kind, superclass, interfaces, " +
                    "annotations, methods, @Bean definitions, and Spring conditions.",
                schema(
                    mapOf(
                        "fqn" to mapOf("type" to "string", "description" to "Class fully-qualified name."),
                        "project_dir" to projectDir,
                    ),
                    "fqn",
                ),
            ),
            tool(
                "get_bean_definitions",
                "Find @Bean definitions by bean type or by declaring configuration class, " +
                    "including each bean method's @ConditionalOn* conditions.",
                schema(
                    withPaging(mapOf("fqn" to mapOf("type" to "string", "description" to "Bean type FQN or configuration class FQN."))),
                    "fqn",
                ),
            ),
            tool(
                "explain_conditional",
                "Explain when an auto-configuration class activates: its class-level " +
                    "@ConditionalOn* conditions plus every @Bean method's conditions.",
                schema(
                    mapOf(
                        "fqn" to mapOf("type" to "string", "description" to "Configuration class fully-qualified name."),
                        "project_dir" to projectDir,
                    ),
                    "fqn",
                ),
            ),
            tool(
                "find_string",
                "Search string constants extracted from bytecode by substring — find which " +
                    "jar/class owns a property key, HTTP header, or log message.",
                schema(
                    withPaging(mapOf("text" to mapOf("type" to "string", "description" to "Substring to search for."))),
                    "text",
                ),
            ),
            tool(
                "list_extension_points",
                "List SPI extension points (spring.factories keys, auto-configuration " +
                    "imports, java.util.ServiceLoader services) with registration counts; pass arg " +
                    "to list the registrations for one key or mechanism.",
                schema(
                    withPaging(mapOf("arg" to mapOf("type" to "string", "description" to "Optional SPI key or mechanism (autoconfig.imports | spring.factories | services)."))),
                ),
            ),
            tool(
                "find_class",
                "Find Java classes by simple name, fully-qualified name, package prefix, " +
                    "or substring across indexed dependency jars.",
                schema(
                    withPaging(mapOf("text" to mapOf("type" to "string", "description" to "Class simple name, FQN, package prefix, or substring."))),
                    "text",
                ),
            ),
            tool(
                "find_method",
                "Find Java methods by method name, substring, or Class#method shape.",
                schema(
                    withPaging(mapOf("text" to mapOf("type" to "string", "description" to "Method name, substring, or Class#method."))),
                    "text",
                ),
            ),
            tool(
                "list_packages",
                "List Java packages grouped by source jar. Pass arg to restrict to a package prefix.",
                schema(
                    withPaging(mapOf("arg" to mapOf("type" to "string", "description" to "Optional package prefix."))),
                ),
            ),
            tool(
                "search_symbols",
                "Broad retrieval entrypoint for agents: search classes, methods, annotations, " +
                    "SPI registrations, Spring config properties, and string constants before opening a result.",
                schema(
                    withPaging(mapOf("text" to mapOf("type" to "string", "description" to "Text to search for."))),
                    "text",
                ),
            ),
            tool(
                "open_symbol",
                "Open a symbol returned by search_symbols. Use a class FQN or Class#method.",
                schema(
                    mapOf(
                        "fqn" to mapOf("type" to "string", "description" to "Class FQN or Class#method symbol."),
                        "project_dir" to projectDir,
                    ),
                    "fqn",
                ),
            ),
            tool(
                "dependency_precise",
                "Resolve an import, class, Class#method, Maven coordinate, artifact id, " +
                    "or jar filename to the owning jar; include Maven dependency path/provenance and " +
                    "bounded source/build-file usages so agents can confirm both dependency presence " +
                    "and project consumption.",
                schema(
                    mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "Import line, class FQN/simple name, Class#method, group:artifact, artifact id, or jar filename.",
                        ),
                        "project_dir" to projectDir,
                        "page_size" to mapOf(
                            "type" to "integer",
                            "description" to "Max origins and source usage rows to return (default 20).",
                        ),
                        "no_source_grep" to mapOf(
                            "type" to "boolean",
                            "description" to "Skip bounded source/build-file usage search.",
                        ),
                    ),
                    "text",
                ),
            ),
            tool(
                "class_origin",
                "Resolve a class simple name or FQN to the owning jar; include Maven " +
                    "dependency provenance and bounded source/build-file usages. Use find_class " +
                    "for fuzzy class search.",
                schema(
                    mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "Class simple name, class FQN, or import line.",
                        ),
                        "project_dir" to projectDir,
                        "page_size" to mapOf(
                            "type" to "integer",
                            "description" to "Max origins and source usage rows to return (default 20).",
                        ),
                        "no_source_grep" to mapOf(
                            "type" to "boolean",
                            "description" to "Skip bounded source/build-file usage search.",
                        ),
                    ),
                    "text",
                ),
            ),
            tool(
                "find_artifact",
                "Find a dependency artifact or jar by group:artifact, group:artifact:version, " +
                    "artifact id, or jar filename; returns the indexed jar plus dependency provenance.",
                schema(
                    mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "group:artifact, group:artifact:version, artifact id, or jar filename.",
                        ),
                        "project_dir" to projectDir,
                        "page_size" to mapOf(
                            "type" to "integer",
                            "description" to "Max origins and source usage rows to return (default 20).",
                        ),
                        "no_source_grep" to mapOf(
                            "type" to "boolean",
                            "description" to "Skip bounded source/build-file usage search.",
                        ),
                    ),
                    "text",
                ),
            ),
            tool(
                "why_dependency",
                "Explain why a Maven dependency jar is present. Accepts group:artifact, " +
                    "group:artifact:version, jar filename, or a class FQN.",
                schema(
                    mapOf(
                        "fqn" to mapOf("type" to "string", "description" to "Coordinate, jar filename, or class FQN."),
                        "project_dir" to projectDir,
                    ),
                    "fqn",
                ),
            ),
        )
    }
}
