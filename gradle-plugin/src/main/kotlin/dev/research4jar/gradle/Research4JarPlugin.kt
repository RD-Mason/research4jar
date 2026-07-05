package dev.research4jar.gradle

import dev.research4jar.indexer.runIndexPipeline
import dev.research4jar.query.Artifact
import dev.research4jar.query.DepGraphFile
import dev.research4jar.query.Graph
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * `plugins { id("dev.research4jar") }` → `./gradlew research4jarIndex`.
 *
 * Indexes the project's runtime dependency jars in-process (no separate
 * install, no exec of a second build) and captures dependency provenance
 * natively from Gradle's resolution result — the graph Maven projects get
 * from `mvn dependency:tree`, previously unavailable for Gradle builds.
 */
class Research4JarPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("research4jarIndex", Research4JarIndexTask::class.java) { task ->
            task.group = "research4jar"
            task.description =
                "Index runtime dependency jars into the Research4Jar fact database."
        }
    }
}

abstract class Research4JarIndexTask : DefaultTask() {
    /** Override the Research4Jar data home (defaults to the user data dir). */
    @get:Input
    @get:Optional
    abstract val home: Property<String>

    /** Dependency configuration to index; runtimeClasspath by default. */
    @get:Input
    @get:Optional
    abstract val configuration: Property<String>

    @get:Internal
    abstract val projectDirectory: Property<String>

    @get:Internal
    abstract val provenance: Property<GradleProvenance>

    init {
        projectDirectory.convention(project.projectDir.absolutePath)
        // Resolution happens at configuration time capture, execution stays
        // configuration-cache friendly by carrying plain data only.
        provenance.convention(
            project.provider {
                val configurationName = configuration.orNull ?: "runtimeClasspath"
                val resolvable = project.configurations.findByName(configurationName)
                    ?: throw IllegalStateException(
                        "configuration $configurationName not found; " +
                            "apply the java plugin or set research4jarIndex.configuration",
                    )
                GradleProvenance(
                    jars = resolvable.files
                        .map { it.absolutePath }
                        .filter { it.endsWith(".jar") },
                    graph = buildGraph(
                        project.group.toString(),
                        project.name,
                        project.version.toString(),
                        resolvable.incoming.resolutionResult.root,
                    ),
                )
            },
        )
    }

    @TaskAction
    fun index() {
        val data = provenance.get()
        if (data.jars.isEmpty()) {
            throw IllegalStateException("resolved an empty runtime classpath; nothing to index")
        }
        val projectDir = java.nio.file.Paths.get(projectDirectory.get())
        val statistics = runIndexPipeline(
            jars = data.jars.joinToString(","),
            projectDir = projectDir,
            home = home.orNull,
        )
        DepGraphCaptureBridge.write(projectDirectory.get(), data.graph)
        logger.lifecycle(
            "research4jar: indexed {}/{} jars ({} new, {} cached){}",
            statistics.jars_indexed,
            statistics.jars_total,
            statistics.jars_newly_indexed,
            statistics.jars_skipped,
            if (statistics.jars_missing.isEmpty()) "" else ", missing: ${statistics.jars_missing}",
        )
    }
}

/** Plain serializable carrier so the task stays configuration-cache safe. */
data class GradleProvenance(
    val jars: List<String>,
    val graph: Graph,
) : java.io.Serializable

/**
 * Flattens Gradle's resolved component graph into the dependencies.json
 * shape shared with the Maven TGF capture: one artifact per component with
 * first-parent path/depth and direct = declared on the root.
 */
internal fun buildGraph(
    rootGroup: String,
    rootName: String,
    rootVersion: String,
    root: ResolvedComponentResult,
): Graph {
    data class Node(val coordinate: String, val parent: String?, val depth: Int, val path: List<String>)

    val rootCoordinate = "$rootGroup:$rootName:$rootVersion"
    val visited = LinkedHashMap<String, Node>()
    val queue = ArrayDeque<Pair<ResolvedComponentResult, Node>>()
    queue += root to Node(rootCoordinate, parent = null, depth = 0, path = emptyList())

    val seenComponents = HashSet<String>()
    while (queue.isNotEmpty()) {
        val (component, node) = queue.removeFirst()
        val componentKey = component.id.displayName
        if (!seenComponents.add(componentKey)) continue
        if (node.parent != null || component === root) {
            if (component !== root) visited.putIfAbsent(node.coordinate, node)
        }
        for (dependency in component.dependencies) {
            if (dependency !is ResolvedDependencyResult) continue
            val selected = dependency.selected
            val moduleVersion = selected.moduleVersion ?: continue
            val coordinate =
                "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
            if (coordinate == rootCoordinate) continue
            val childPath = node.path + coordinate
            queue += selected to Node(
                coordinate = coordinate,
                parent = node.coordinate,
                depth = childPath.size,
                path = childPath,
            )
        }
    }

    val artifacts = mutableListOf(
        Artifact(
            coordinate = rootCoordinate,
            artifact = "$rootGroup:$rootName",
            group = rootGroup,
            name = rootName,
            version = rootVersion,
            direct = false,
            depth = 0,
            path = emptyList(),
        ),
    )
    for (node in visited.values) {
        val parts = node.coordinate.split(":")
        artifacts += Artifact(
            coordinate = node.coordinate,
            artifact = "${parts[0]}:${parts[1]}",
            group = parts[0],
            name = parts[1],
            version = parts[2],
            scope = "runtime",
            parent = node.parent ?: "",
            direct = node.parent == rootCoordinate,
            depth = node.depth,
            path = node.path,
        )
    }
    artifacts.sortWith(compareBy({ it.depth }, { it.coordinate }))
    return Graph(
        schemaVersion = DepGraphFile.SCHEMA_VERSION,
        buildTool = "gradle",
        artifacts = artifacts,
    )
}
