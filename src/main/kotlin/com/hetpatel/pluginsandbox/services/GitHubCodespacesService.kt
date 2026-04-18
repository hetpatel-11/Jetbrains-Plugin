package com.hetpatel.pluginsandbox.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.RecommendationKind
import com.hetpatel.pluginsandbox.model.SandboxConfig
import java.time.Duration
import java.time.Instant

class GitHubCodespacesService(
    private val commandRunner: CommandRunner = CommandRunner(),
) {
    private val gson = Gson()

    fun ensureCodespace(
        config: SandboxConfig,
        recommendation: Recommendation,
        onLog: (String) -> Unit,
    ): CodespaceDetails {
        validateAuth(onLog)

        val repoFullName = recommendation.repositorySlug
        val existing = listCodespaces(repoFullName)
            .filter { config.branch.isBlank() || it.branch.equals(config.branch, ignoreCase = true) || it.branch.isBlank() }
            .sortedByDescending { it.lastUsedAt.ifBlank { it.createdAt } }
            .firstOrNull()

        val codespaceName = if (existing != null) {
            onLog("Using existing codespace ${existing.name} for $repoFullName (${existing.state}).")
            if (!existing.state.equals("Available", ignoreCase = true)) {
                startCodespace(existing.name, onLog)
            }
            existing.name
        } else {
            val displayName = buildDisplayName(recommendation)
            onLog("Creating a codespace for $repoFullName${if (config.branch.isNotBlank()) " on branch ${config.branch}" else ""}.")
            createCodespace(repoFullName, config, displayName).ensureSuccess("Failed to create a codespace.")
            pollForCodespace(repoFullName, displayName, onLog)
        }

        val details = waitForAvailable(codespaceName, onLog)
        val bootstrap = bootstrapRecommendation(details.name, details.repositoryName, recommendation)
        if (bootstrap.combinedOutput().isNotBlank()) {
            onLog(bootstrap.combinedOutput())
        }

        return details.copy(
            bootstrapSummary = bootstrap.summary(recommendation.kind),
        )
    }

    fun runRemoteCommand(
        codespaceName: String,
        command: String,
        timeout: Duration = Duration.ofMinutes(15),
    ): CommandResult {
        return commandRunner.run(
            listOf(
                "gh", "codespace", "ssh",
                "-c", codespaceName,
                "--",
                "bash", "-lc", command,
            ),
            timeout = timeout,
        )
    }

    private fun validateAuth(onLog: (String) -> Unit) {
        val status = commandRunner.run(listOf("gh", "auth", "status"), timeout = Duration.ofSeconds(15))
        status.ensureSuccess("GitHub CLI authentication is required.")
        val scopesLine = status.combinedOutput()
            .lineSequence()
            .firstOrNull { it.contains("Token scopes:") }
            .orEmpty()
        if (!scopesLine.contains("codespace")) {
            throw IllegalStateException(
                "The active GitHub CLI token is missing the `codespace` scope. Run `gh auth refresh --scopes codespace` and try again.",
            )
        }
        onLog("GitHub CLI authentication looks valid for Codespaces.")
    }

    private fun listCodespaces(repoFullName: String): List<CodespaceSummary> {
        val output = commandRunner.run(
            listOf(
                "gh", "api",
                "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2026-03-10",
                "/repos/$repoFullName/codespaces?per_page=100",
            ),
            timeout = Duration.ofSeconds(30),
        ).ensureSuccess("Failed to list codespaces for $repoFullName.")

        val root = gson.fromJson(output.stdout.ifBlank { "{\"codespaces\":[]}" }, JsonObject::class.java)
        val array = root.getAsJsonArray("codespaces") ?: com.google.gson.JsonArray()
        return array.map { element ->
            val item = element.asJsonObject
            CodespaceSummary(
                name = item.string("name"),
                displayName = item.string("display_name"),
                state = item.string("state"),
                createdAt = item.string("created_at"),
                lastUsedAt = item.string("last_used_at"),
                branch = item.getAsJsonObject("git_status")?.string("ref").orEmpty(),
            )
        }
    }

    private fun createCodespace(
        repoFullName: String,
        config: SandboxConfig,
        displayName: String,
    ): CommandResult {
        val command = mutableListOf(
            "gh", "codespace", "create",
            "-R", repoFullName,
            "-d", displayName,
            "--default-permissions",
            "-s",
        )
        if (config.branch.isNotBlank()) {
            command += listOf("-b", config.branch)
        }
        if (config.devcontainerPath.isNotBlank()) {
            command += listOf("--devcontainer-path", config.devcontainerPath)
        }
        if (config.machine.isNotBlank()) {
            command += listOf("-m", config.machine)
        }
        return commandRunner.run(command, timeout = Duration.ofMinutes(20))
    }

    private fun startCodespace(
        codespaceName: String,
        onLog: (String) -> Unit,
    ) {
        onLog("Starting codespace $codespaceName.")
        commandRunner.run(
            listOf(
                "gh", "api",
                "-X", "POST",
                "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2026-03-10",
                "/user/codespaces/$codespaceName/start",
            ),
            timeout = Duration.ofMinutes(5),
        ).ensureSuccess("Failed to start codespace $codespaceName.")
    }

    private fun waitForAvailable(
        codespaceName: String,
        onLog: (String) -> Unit,
    ): CodespaceDetails {
        repeat(30) { attempt ->
            val details = viewCodespace(codespaceName)
            onLog("Codespace ${details.name} state: ${details.state}.")
            if (details.state.equals("Available", ignoreCase = true)) {
                return details
            }
            if (attempt < 29) {
                Thread.sleep(5_000)
            }
        }
        throw IllegalStateException("Codespace $codespaceName did not become available in time.")
    }

    private fun viewCodespace(codespaceName: String): CodespaceDetails {
        val output = commandRunner.run(
            listOf(
                "gh", "api",
                "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2026-03-10",
                "/user/codespaces/$codespaceName",
            ),
            timeout = Duration.ofSeconds(30),
        ).ensureSuccess("Failed to view codespace $codespaceName.")
        val item = gson.fromJson(output.stdout, JsonObject::class.java)
        return CodespaceDetails(
            name = item.string("name"),
            displayName = item.string("display_name"),
            state = item.string("state"),
            branch = item.getAsJsonObject("git_status")?.string("ref").orEmpty(),
            machineDisplayName = item.getAsJsonObject("machine")?.string("display_name").orEmpty(),
            location = item.string("location"),
            devcontainerPath = item.string("devcontainer_path"),
            webUrl = item.string("web_url"),
            repositoryName = item.getAsJsonObject("repository")?.string("name").orEmpty(),
            bootstrapSummary = "",
        )
    }

    private fun pollForCodespace(
        repoFullName: String,
        displayName: String,
        onLog: (String) -> Unit,
    ): String {
        repeat(24) { attempt ->
            val match = listCodespaces(repoFullName)
                .filter { it.displayName == displayName }
                .maxByOrNull { parseInstant(it.createdAt) }
            if (match != null) {
                onLog("Created codespace ${match.name}.")
                return match.name
            }
            if (attempt < 23) {
                Thread.sleep(5_000)
            }
        }
        throw IllegalStateException("Codespace creation did not return a visible codespace in time.")
    }

    private fun bootstrapRecommendation(
        codespaceName: String,
        repositoryName: String,
        recommendation: Recommendation,
    ): CommandResult {
        val workspace = "/workspaces/$repositoryName"
        val command = when (recommendation.kind) {
            RecommendationKind.FAST_API_SIDECAR -> """
                set -e
                cd ${shellQuote(workspace)}
                mkdir -p .plugin-sandbox/fastapi-sidecar
                cat > .plugin-sandbox/fastapi-sidecar/main.py <<'PY'
from fastapi import FastAPI

app = FastAPI()

@app.get("/healthz")
def healthz():
    return {"status": "ok"}
PY
                cat > .plugin-sandbox/fastapi-sidecar/requirements.txt <<'REQ'
fastapi
uvicorn
REQ
                printf 'Created FastAPI sidecar scaffold at .plugin-sandbox/fastapi-sidecar\n'
            """.trimIndent()
            RecommendationKind.DIRECT_IN_REPO -> """
                set -e
                cd ${shellQuote(workspace)}
                git status --short || true
                printf 'Direct in-repo sandbox attached to %s\n' ${shellQuote(workspace)}
            """.trimIndent()
            RecommendationKind.ADAPTER_SANDBOX -> """
                set -e
                cd ${shellQuote(workspace)}
                mkdir -p .plugin-sandbox/adapter-prototype
                cat > .plugin-sandbox/adapter-prototype/README.md <<'MD'
# Adapter Prototype

Use this area to validate a boundary-first implementation before wiring it into the main modules.
MD
                printf 'Created adapter prototype scaffold at .plugin-sandbox/adapter-prototype\n'
            """.trimIndent()
        }
        return runRemoteCommand(codespaceName, command, timeout = Duration.ofMinutes(5))
    }

    private fun buildDisplayName(recommendation: Recommendation): String =
        "plugin-sandbox-${recommendation.kind.name.lowercase().replace('_', '-')}-${Instant.now().epochSecond}"

    private fun JsonObject.string(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
}

data class CodespaceSummary(
    val name: String,
    val displayName: String,
    val state: String,
    val createdAt: String,
    val lastUsedAt: String,
    val branch: String,
)

data class CodespaceDetails(
    val name: String,
    val displayName: String,
    val state: String,
    val branch: String,
    val machineDisplayName: String,
    val location: String,
    val devcontainerPath: String,
    val webUrl: String,
    val repositoryName: String,
    val bootstrapSummary: String,
)

private fun CommandResult.summary(kind: RecommendationKind): String {
    val output = combinedOutput()
    return if (output.isNotBlank()) {
        output.lines().take(6).joinToString("\n")
    } else {
        when (kind) {
            RecommendationKind.FAST_API_SIDECAR -> "FastAPI scaffold created in the codespace sandbox."
            RecommendationKind.DIRECT_IN_REPO -> "In-repo sandbox attached without creating extra scaffolding."
            RecommendationKind.ADAPTER_SANDBOX -> "Adapter prototype scaffold created in the codespace sandbox."
        }
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
