package com.hetpatel.pluginsandbox.services

import com.google.gson.GsonBuilder
import com.hetpatel.pluginsandbox.model.McpBridgeInfo
import com.hetpatel.pluginsandbox.model.Recommendation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

class McpBridgeManager(
    private val repoRoot: Path?,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val stateFilePath: Path = resolveStateFilePath()
    private val configFilePath: Path = resolveConfigFilePath()
    private val scriptPath: Path = extractServerScript()

    fun writeActiveSandbox(
        codespaceName: String,
        repositoryName: String,
        branch: String,
        browserUrl: String,
        recommendation: Recommendation,
        workspacePath: String,
    ) {
        Files.createDirectories(stateFilePath.parent)
        val payload = mapOf(
            "version" to 1,
            "updatedAt" to Instant.now().toString(),
            "projectRoot" to repoRoot?.toAbsolutePath()?.toString().orEmpty(),
            "codespaceName" to codespaceName,
            "repositoryName" to repositoryName,
            "repositorySlug" to recommendation.repositorySlug,
            "repositoryUrl" to recommendation.repositoryUrl,
            "workspacePath" to workspacePath,
            "branch" to branch,
            "browserUrl" to browserUrl,
            "recommendationTitle" to recommendation.title,
        )
        Files.writeString(
            stateFilePath,
            gson.toJson(payload),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun clearActiveSandbox() {
        Files.deleteIfExists(stateFilePath)
    }

    fun info(status: String? = null): McpBridgeInfo =
        McpBridgeInfo(
            serverScriptPath = scriptPath.toAbsolutePath().toString(),
            stateFilePath = stateFilePath.toAbsolutePath().toString(),
            configJson = buildConfigJson().also(::writeConfigFile),
            status = status ?: if (Files.exists(stateFilePath)) {
                "AI chat MCP bridge is bound to the last launched sandbox."
            } else {
                "Launch `Try it out` to bind AI chat tools to a live sandbox."
            },
        )

    private fun buildConfigJson(): String =
        gson.toJson(
            mapOf(
                "mcpServers" to mapOf(
                    "pluginSandbox" to mapOf(
                        "command" to "python3",
                        "args" to listOf(
                            scriptPath.toAbsolutePath().toString(),
                            "--state-file",
                            stateFilePath.toAbsolutePath().toString(),
                        ),
                    ),
                ),
            ),
        )

    private fun writeConfigFile(configJson: String) {
        Files.createDirectories(configFilePath.parent)
        Files.writeString(
            configFilePath,
            configJson,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun resolveStateFilePath(): Path {
        val base = repoRoot ?: Path.of(System.getProperty("java.io.tmpdir"), "plugin-sandbox")
        return base.resolve(".plugin-sandbox").resolve("mcp-state.json")
    }

    private fun resolveConfigFilePath(): Path {
        val base = repoRoot ?: Path.of(System.getProperty("java.io.tmpdir"), "plugin-sandbox")
        return base.resolve(".ai").resolve("mcp").resolve("mcp.json")
    }

    private fun extractServerScript(): Path {
        val outputDir = Path.of(System.getProperty("java.io.tmpdir"), "plugin-sandbox-mcp")
        Files.createDirectories(outputDir)
        val target = outputDir.resolve("sandbox_bridge.py")
        val resource = javaClass.classLoader.getResourceAsStream("mcp/sandbox_bridge.py")
            ?: throw IllegalStateException("Bundled MCP bridge script is missing.")
        resource.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        target.toFile().setExecutable(true, true)
        return target
    }
}
