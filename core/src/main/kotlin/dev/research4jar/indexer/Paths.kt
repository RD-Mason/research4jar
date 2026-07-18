package dev.research4jar.indexer

import dev.research4jar.runtime.WorkingDirectoryContext
import java.nio.file.Path
import java.nio.file.Paths

data class DataPaths(
    val home: Path,
    val manifest: Path = home.resolve("manifest.db"),
    val shards: Path = home.resolve("shards"),
    val sessions: Path = home.resolve("sessions"),
)

object Research4JarPaths {
    fun resolve(
        explicitHome: String? = null,
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
    ): DataPaths {
        val home = when {
            !explicitHome.isNullOrBlank() -> Paths.get(explicitHome)
            !environment["RESEARCH4JAR_HOME"].isNullOrBlank() ->
                Paths.get(environment.getValue("RESEARCH4JAR_HOME"))

            osName.lowercase().let { it.contains("mac") || it.contains("darwin") } ->
                Paths.get(userHome, "Library", "Application Support", "research4jar")

            osName.lowercase().startsWith("windows") -> {
                val localAppData = environment["LOCALAPPDATA"]
                    ?: error("LOCALAPPDATA is required on Windows when RESEARCH4JAR_HOME is unset")
                Paths.get(localAppData, "research4jar")
            }

            else -> {
                val xdg = environment["XDG_DATA_HOME"]
                if (xdg.isNullOrBlank()) {
                    Paths.get(userHome, ".local", "share", "research4jar")
                } else {
                    Paths.get(xdg, "research4jar")
                }
            }
        }
        return DataPaths(WorkingDirectoryContext.resolve(home))
    }
}
