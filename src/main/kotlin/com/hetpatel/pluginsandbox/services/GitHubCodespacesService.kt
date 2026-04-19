package com.hetpatel.pluginsandbox.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.RecommendationKind
import com.hetpatel.pluginsandbox.model.SandboxConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class GitHubCodespacesService(
    private val repoRoot: Path? = null,
    private val commandRunner: CommandRunner = CommandRunner(),
) {
    private val gson = Gson()
    private val launcherRepositorySlug: String by lazy { detectLauncherRepository() }

    fun ensureCodespace(
        config: SandboxConfig,
        recommendation: Recommendation,
        onLog: (String) -> Unit,
    ): CodespaceDetails {
        validateAuth(onLog)

        val repoFullName = launcherRepositorySlug
        val displayPrefix = buildDisplayPrefix(recommendation)
        val branchToUse = resolveBranch(repoFullName, config.branch, onLog)
        val machineToUse = resolveMachine(repoFullName, branchToUse, config.machine, onLog)
        val existing = listCodespaces(repoFullName)
            .filter { it.displayName.startsWith(displayPrefix) }
            .filter { branchToUse.isBlank() || it.branch.equals(branchToUse, ignoreCase = true) || it.branch.isBlank() }
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
            onLog(
                "Creating a launcher codespace for $repoFullName${if (branchToUse.isNotBlank()) " on branch $branchToUse" else ""} to boot ${recommendation.repositorySlug}.",
            )
            createCodespace(repoFullName, config, branchToUse, machineToUse, displayName).ensureSuccess("Failed to create a codespace.")
            pollForCodespace(repoFullName, displayName, onLog)
        }

        val details = waitForAvailable(codespaceName, onLog)
        ensureLauncherWorkspace(details.name, recommendation, onLog)
        return details.copy(
            bootstrapSummary = "Launcher repo ${details.repositoryName} is ready. It auto-cloned ${recommendation.repositorySlug} into /workspaces/target, installed dependencies, and started the app on port 8000.",
        )
    }

    fun commandPreview(
        config: SandboxConfig,
        recommendation: Recommendation,
    ): String {
        return buildString {
            append("gh codespace create -R ")
            append(launcherRepositorySlug)
            if (config.branch.isNotBlank()) {
                append(" -b ")
                append(config.branch)
            }
            if (config.devcontainerPath.isNotBlank()) {
                append(" --devcontainer-path ")
                append(config.devcontainerPath)
            }
            if (config.machine.isNotBlank()) {
                append(" -m ")
                append(config.machine)
            }
            append(" -d ")
            append(buildDisplayName(recommendation))
        }
    }

    fun runRemoteCommand(
        codespaceName: String,
        command: String,
        timeout: Duration = Duration.ofMinutes(15),
    ): CommandResult {
        val sshConfig = Files.createTempFile("plugin-sandbox-codespace-", ".ssh")
        return try {
            val config = commandRunner.run(
                listOf("gh", "codespace", "ssh", "-c", codespaceName, "--config"),
                timeout = Duration.ofSeconds(30),
            ).ensureSuccess("Failed to generate SSH config for codespace $codespaceName.")
            Files.writeString(sshConfig, config.stdout)
            val host = Regex("""(?m)^Host\s+(.+)$""").find(config.stdout)?.groupValues?.get(1)
                ?: throw IllegalStateException("Failed to resolve SSH host alias for codespace $codespaceName.")
            commandRunner.run(
                listOf(
                    "bash",
                    "-lc",
                    "ssh -F ${shellQuote(sshConfig.toString())} ${shellQuote(host)} bash -lc ${shellQuote(command)}",
                ),
                timeout = timeout,
            )
        } finally {
            Files.deleteIfExists(sshConfig)
        }
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
        branch: String,
        machine: String,
        displayName: String,
    ): CommandResult {
        val command = mutableListOf(
            "gh", "codespace", "create",
            "-R", repoFullName,
            "-d", displayName,
            "--default-permissions",
        )
        if (branch.isNotBlank()) {
            command += listOf("-b", branch)
        }
        if (machine.isNotBlank()) {
            command += listOf("-m", machine)
        }
        if (config.devcontainerPath.isNotBlank()) {
            command += listOf("--devcontainer-path", config.devcontainerPath)
        }
        if (config.machine.isNotBlank()) {
            command += listOf("-m", config.machine)
        }
        return commandRunner.run(command, timeout = Duration.ofMinutes(20))
    }

    private fun resolveBranch(
        repoFullName: String,
        requestedBranch: String,
        onLog: (String) -> Unit,
    ): String {
        if (requestedBranch.isBlank()) {
            val defaultBranch = fetchDefaultBranch(repoFullName)
            if (defaultBranch.isNotBlank()) {
                onLog("Using default branch $defaultBranch for $repoFullName.")
            }
            return defaultBranch
        }

        if (branchExists(repoFullName, requestedBranch)) {
            return requestedBranch
        }

        val defaultBranch = fetchDefaultBranch(repoFullName)
        onLog("Branch $requestedBranch is invalid for $repoFullName. Falling back to default branch $defaultBranch.")
        return defaultBranch
    }

    private fun resolveMachine(
        repoFullName: String,
        branch: String,
        requestedMachine: String,
        onLog: (String) -> Unit,
    ): String {
        if (requestedMachine.isNotBlank()) {
            onLog("Using configured machine type $requestedMachine.")
            return requestedMachine
        }

        val machine = fetchAvailableMachines(repoFullName, branch).firstOrNull().orEmpty()
        if (machine.isNotBlank()) {
            onLog("Using machine type $machine for $repoFullName.")
        }
        return machine
    }

    private fun branchExists(
        repoFullName: String,
        branch: String,
    ): Boolean =
        commandRunner.run(
            listOf(
                "gh", "api",
                "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2026-03-10",
                "/repos/$repoFullName/branches/$branch",
            ),
            timeout = Duration.ofSeconds(20),
        ).exitCode == 0

    private fun fetchDefaultBranch(repoFullName: String): String {
        val output = commandRunner.run(
            listOf(
                "gh", "repo", "view", repoFullName,
                "--json", "defaultBranchRef",
            ),
            timeout = Duration.ofSeconds(20),
        ).ensureSuccess("Failed to fetch the default branch for $repoFullName.")
        val root = gson.fromJson(output.stdout, JsonObject::class.java)
        return root.getAsJsonObject("defaultBranchRef")?.string("name").orEmpty()
    }

    private fun fetchAvailableMachines(
        repoFullName: String,
        branch: String,
    ): List<String> {
        val endpoint = buildString {
            append("/repos/")
            append(repoFullName)
            append("/codespaces/machines")
            if (branch.isNotBlank()) {
                append("?ref=")
                append(branch)
            }
        }
        val output = commandRunner.run(
            listOf(
                "gh", "api",
                "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2026-03-10",
                endpoint,
            ),
            timeout = Duration.ofSeconds(20),
        ).ensureSuccess("Failed to fetch available machine types for $repoFullName.")
        val root = gson.fromJson(output.stdout, JsonObject::class.java)
        val machines = root.getAsJsonArray("machines") ?: com.google.gson.JsonArray()
        return machines.mapNotNull { element ->
            element.asJsonObject?.get("name")?.takeUnless { it.isJsonNull }?.asString
        }
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

    private fun ensureLauncherWorkspace(
        codespaceName: String,
        recommendation: Recommendation,
        onLog: (String) -> Unit,
    ) {
        if (waitForLauncherWorkspace(codespaceName, onLog, attempts = 6)) {
            return
        }

        onLog("Launcher startup did not complete automatically. Running fallback bootstrap commands inside $codespaceName.")
        val bootstrap = bootstrapLauncherWorkspace(codespaceName, recommendation)
        if (bootstrap.combinedOutput().isNotBlank()) {
            onLog(bootstrap.combinedOutput())
        }

        if (waitForLauncherWorkspace(codespaceName, onLog, attempts = 12)) {
            return
        }
        throw IllegalStateException("Launcher startup did not finish in time. /workspaces/target was not ready.")
    }

    private fun waitForLauncherWorkspace(
        codespaceName: String,
        onLog: (String) -> Unit,
        attempts: Int,
    ): Boolean {
        repeat(attempts) { attempt ->
            val result = runRemoteCommand(
                codespaceName = codespaceName,
                command = "test -d /workspaces/target/.git && test -e /workspaces/target",
                timeout = Duration.ofSeconds(20),
            )
            if (result.exitCode == 0) {
                onLog("Launcher workspace /workspaces/target is ready.")
                return true
            }
            if (attempt < attempts - 1) {
                onLog("Waiting for launcher startup to finish inside ${codespaceName}.")
                Thread.sleep(5_000)
            }
        }
        return false
    }

    private fun bootstrapLauncherWorkspace(
        codespaceName: String,
        recommendation: Recommendation,
    ): CommandResult {
        val targetDevcontainerJson = """
            {
              "name": "Ephemeral Sandbox Target",
              "image": "mcr.microsoft.com/devcontainers/python:3.10",
              "postCreateCommand": "pip install fastapi uvicorn git",
              "postStartCommand": "bash .devcontainer/start.sh",
              "forwardPorts": [8000],
              "portsAttributes": {
                "8000": {
                  "label": "Sandbox App",
                  "onAutoForward": "openBrowser"
                }
              }
            }
        """.trimIndent()
        val command = when (recommendation.kind) {
            RecommendationKind.FAST_API_SIDECAR -> """
                set -e
                rm -f /workspaces/Jetbrains-Plugin/devcontainer.txt
                TARGET_REPO=${shellQuote(recommendation.repositoryUrl)}
                TARGET_DIR=/workspaces/target
                rm -rf "${'$'}TARGET_DIR"
                git clone --depth 1 "${'$'}TARGET_REPO" "${'$'}TARGET_DIR"
                rm -f "${'$'}TARGET_DIR/devcontainer.txt"
                mkdir -p "${'$'}TARGET_DIR/.devcontainer"
                cat > "${'$'}TARGET_DIR/.devcontainer/devcontainer.json" <<'JSON'
                $targetDevcontainerJson
                JSON
                cat > "${'$'}TARGET_DIR/.devcontainer/start.sh" <<'SH'
                #!/bin/bash
                set -e
                python -m pip install --upgrade pip
                if [ -f requirements.txt ]; then
                  pip install -r requirements.txt
                else
                  pip install fastapi uvicorn
                fi
                python -m uvicorn docs_src.body_updates.tutorial001_py310:app --host 0.0.0.0 --port 8000
                SH
                chmod +x "${'$'}TARGET_DIR/.devcontainer/start.sh"
                cd "${'$'}TARGET_DIR"
                if command -v lsof >/dev/null 2>&1; then
                  pids="${'$'}(lsof -ti tcp:8000 || true)"
                  if [ -n "${'$'}pids" ]; then
                    kill ${'$'}pids >/dev/null 2>&1 || true
                  fi
                fi
                nohup bash .devcontainer/start.sh >/tmp/plugin-sandbox-fastapi.log 2>&1 < /dev/null &
                echo "Fallback bootstrap finished for FastAPI."
            """.trimIndent()
            RecommendationKind.DIRECT_IN_REPO -> """
                set -e
                rm -f /workspaces/Jetbrains-Plugin/devcontainer.txt
                TARGET_REPO=${shellQuote(recommendation.repositoryUrl)}
                TARGET_DIR=/workspaces/target
                rm -rf "${'$'}TARGET_DIR"
                git clone --depth 1 "${'$'}TARGET_REPO" "${'$'}TARGET_DIR"
                rm -f "${'$'}TARGET_DIR/devcontainer.txt"
                mkdir -p "${'$'}TARGET_DIR/.devcontainer"
                cat > "${'$'}TARGET_DIR/.devcontainer/devcontainer.json" <<'JSON'
                $targetDevcontainerJson
                JSON
                cat > "${'$'}TARGET_DIR/.devcontainer/start.sh" <<'SH'
                #!/bin/bash
                set -e
                python -m pip install --upgrade pip
                pip install -e .
                pip install -e examples/tutorial
                cd examples/tutorial
                python -m flask --app flaskr init-db >/tmp/plugin-sandbox-flask-init.log 2>&1 || true
                python -m flask --app flaskr run --host 0.0.0.0 --port 8000
                SH
                chmod +x "${'$'}TARGET_DIR/.devcontainer/start.sh"
                cd "${'$'}TARGET_DIR"
                if command -v lsof >/dev/null 2>&1; then
                  pids="${'$'}(lsof -ti tcp:8000 || true)"
                  if [ -n "${'$'}pids" ]; then
                    kill ${'$'}pids >/dev/null 2>&1 || true
                  fi
                fi
                nohup bash .devcontainer/start.sh >/tmp/plugin-sandbox-flask.log 2>&1 < /dev/null &
                echo "Fallback bootstrap finished for Flask."
            """.trimIndent()
            RecommendationKind.ADAPTER_SANDBOX -> """
                set -e
                rm -f /workspaces/Jetbrains-Plugin/devcontainer.txt
                TARGET_REPO=${shellQuote(recommendation.repositoryUrl)}
                TARGET_DIR=/workspaces/target
                rm -rf "${'$'}TARGET_DIR"
                git clone --depth 1 "${'$'}TARGET_REPO" "${'$'}TARGET_DIR"
                rm -f "${'$'}TARGET_DIR/devcontainer.txt"
                mkdir -p "${'$'}TARGET_DIR/.devcontainer"
                cat > "${'$'}TARGET_DIR/.devcontainer/devcontainer.json" <<'JSON'
                $targetDevcontainerJson
                JSON
                cat > "${'$'}TARGET_DIR/.devcontainer/start.sh" <<'SH'
                #!/bin/bash
                set -e
                python -m pip install --upgrade pip
                pip install -e .
                TARGET_DIR="$(pwd)"
                RUNTIME_DIR="${'$'}TARGET_DIR/.plugin-sandbox-runtime/django-demo"
                mkdir -p "${'$'}RUNTIME_DIR"
                if [ ! -f "${'$'}RUNTIME_DIR/manage.py" ]; then
                  django-admin startproject sandbox_app "${'$'}RUNTIME_DIR"
                fi
                cd "${'$'}RUNTIME_DIR"
                python manage.py migrate --noinput >/tmp/plugin-sandbox-django-migrate.log 2>&1
                python manage.py runserver 0.0.0.0:8000
                SH
                chmod +x "${'$'}TARGET_DIR/.devcontainer/start.sh"
                cd "${'$'}TARGET_DIR"
                if command -v lsof >/dev/null 2>&1; then
                  pids="${'$'}(lsof -ti tcp:8000 || true)"
                  if [ -n "${'$'}pids" ]; then
                    kill ${'$'}pids >/dev/null 2>&1 || true
                  fi
                fi
                nohup bash .devcontainer/start.sh >/tmp/plugin-sandbox-django.log 2>&1 < /dev/null &
                echo "Fallback bootstrap finished for Django."
            """.trimIndent()
        }
        return runRemoteCommand(codespaceName, command, timeout = Duration.ofMinutes(10))
    }

    private fun buildDisplayPrefix(recommendation: Recommendation): String =
        when (recommendation.kind) {
            RecommendationKind.FAST_API_SIDECAR -> "ps-fastapi"
            RecommendationKind.DIRECT_IN_REPO -> "ps-flask"
            RecommendationKind.ADAPTER_SANDBOX -> "ps-django"
        }

    private fun buildDisplayName(recommendation: Recommendation): String =
        "${buildDisplayPrefix(recommendation)}-${Instant.now().epochSecond}"

    private fun detectLauncherRepository(): String {
        val root = repoRoot?.toAbsolutePath()?.toString() ?: "."
        val remote = commandRunner.run(
            listOf("git", "-C", root, "remote", "get-url", "origin"),
            timeout = Duration.ofSeconds(10),
        ).ensureSuccess("Failed to determine the launcher repository remote.")

        val value = remote.stdout.trim()
        parseRepositorySlug(value)?.let { return it }
        throw IllegalStateException(
            "The launcher repository remote `$value` is not a supported GitHub origin. Expected `owner/repo` on github.com.",
        )
    }

    private fun parseRepositorySlug(remote: String): String? {
        val normalized = remote.removeSuffix(".git")
        val httpsMatch = Regex("""https://github\.com/([^/]+/[^/]+)$""").find(normalized)
        if (httpsMatch != null) {
            return httpsMatch.groupValues[1]
        }
        val sshMatch = Regex("""git@github\.com:([^/]+/[^/]+)$""").find(normalized)
        if (sshMatch != null) {
            return sshMatch.groupValues[1]
        }
        return null
    }

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

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
