package com.hetpatel.pluginsandbox.services

import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.ScanResult
import com.hetpatel.pluginsandbox.model.ScanSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class ScanOrchestrator(
    private val codespacesService: GitHubCodespacesService,
    private val commandRunner: CommandRunner = CommandRunner(),
) : Disposable {
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val generation = AtomicInteger(0)
    private val runningTasks = CopyOnWriteArrayList<Future<*>>()

    fun startScan(
        codespaceName: String,
        workspacePath: String,
        recommendation: Recommendation,
        onUpdate: (List<ScanResult>) -> Unit,
        onLog: (String) -> Unit,
    ) {
        cancelCurrent()
        val runId = generation.incrementAndGet()
        val scanSpecs = scanSpecs()
        publish(runId, scanSpecs.map { it.runningResult() }, onUpdate)

        val scanTask = executor.submit {
            val workspace = workspacePath
            onLog("Running vulnerability checks inside codespace $codespaceName against ${recommendation.repositorySlug} in $workspace.")

            val results = MutableList(scanSpecs.size) { index -> scanSpecs[index].runningResult() }
            scanSpecs.forEachIndexed { index, spec ->
                val task = executor.submit {
                    val result = runCatching {
                        onLog("Running ${spec.toolName} inside the sandbox against ${recommendation.repositorySlug}.")
                        spec.interpret(
                            codespacesService.runRemoteCommand(
                                codespaceName = codespaceName,
                                command = "cd ${shellQuote(workspace)} && ${spec.command}",
                                timeout = Duration.ofMinutes(20),
                            ),
                        )
                    }.getOrElse { error ->
                        ScanResult(
                            toolName = spec.toolName,
                            statusLabel = "Failed",
                            findings = error.message ?: "Unknown scan failure.",
                            recommendation = "Inspect the scan failure before trusting the sandbox result.",
                            severity = ScanSeverity.HIGH_RISK,
                        )
                    }
                    synchronized(results) {
                        results[index] = result
                        publish(runId, results.toList(), onUpdate)
                    }
                }
                runningTasks += task
            }
        }
        runningTasks += scanTask
    }

    override fun dispose() {
        cancelCurrent()
    }

    private fun publish(
        runId: Int,
        results: List<ScanResult>,
        onUpdate: (List<ScanResult>) -> Unit,
    ) {
        if (generation.get() != runId) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (generation.get() == runId) {
                onUpdate(results)
            }
        }
    }

    private fun cancelCurrent() {
        generation.incrementAndGet()
        runningTasks.forEach { it.cancel(true) }
        runningTasks.clear()
    }

    private fun scanSpecs(): List<ScanSpec> = listOf(
        ScanSpec(
            toolName = "gitleaks",
            command = "if command -v gitleaks >/dev/null 2>&1; then gitleaks detect --no-banner --source . --redact; else echo '__PLUGIN_UNAVAILABLE__'; fi",
            parser = ::interpretGitleaks,
        ),
        ScanSpec(
            toolName = "trivy fs",
            command = "if command -v trivy >/dev/null 2>&1; then trivy fs --quiet --severity MEDIUM,HIGH,CRITICAL .; else echo '__PLUGIN_UNAVAILABLE__'; fi",
            parser = { interpretGeneral(it, "Dependency and filesystem vulnerabilities were reported.", "Review the vulnerable packages or config issues before promoting this sandbox path.") },
        ),
        ScanSpec(
            toolName = "osv-scanner",
            command = "if command -v osv-scanner >/dev/null 2>&1; then osv-scanner -r .; else echo '__PLUGIN_UNAVAILABLE__'; fi",
            parser = { interpretGeneral(it, "OSV advisories were reported for the current workspace.", "Upgrade or isolate affected packages before implementing the chosen approach.") },
        ),
        ScanSpec(
            toolName = "heuristics",
            command = "if command -v rg >/dev/null 2>&1; then rg -n -e 'curl\\s+.*\\|\\s*sh' -e 'wget\\s+.*\\|\\s*sh' -e 'postinstall' -e 'eval\\(' -e 'process\\.env\\.[A-Z0-9_]{8,}' -g '!node_modules' -g '!dist' -g '!build' .; else echo '__PLUGIN_UNAVAILABLE__'; fi",
            parser = ::interpretHeuristics,
        ),
        ScanSpec(
            toolName = "package audit",
            command = """
                if [ -f package.json ]; then
                  if [ -f pnpm-lock.yaml ] && command -v pnpm >/dev/null 2>&1; then
                    pnpm audit --prod
                  elif command -v npm >/dev/null 2>&1; then
                    npm audit --omit=dev
                  else
                    echo '__PLUGIN_UNAVAILABLE__'
                  fi
                else
                  echo '__PLUGIN_NOT_APPLICABLE__'
                fi
            """.trimIndent(),
            parser = { interpretGeneral(it, "JavaScript dependency audit reported issues.", "Resolve the reported package vulnerabilities before implementing this recommendation.") },
        ),
        ScanSpec(
            toolName = "python audit",
            command = """
                if [ -f requirements.txt ] || [ -f pyproject.toml ]; then
                  if command -v pip-audit >/dev/null 2>&1; then
                    pip-audit
                  else
                    echo '__PLUGIN_UNAVAILABLE__'
                  fi
                else
                  echo '__PLUGIN_NOT_APPLICABLE__'
                fi
            """.trimIndent(),
            parser = { interpretGeneral(it, "Python dependency audit reported issues.", "Fix the reported Python package vulnerabilities before promoting this path.") },
        ),
        ScanSpec(
            toolName = "cargo audit",
            command = """
                if [ -f Cargo.toml ]; then
                  if command -v cargo-audit >/dev/null 2>&1; then
                    cargo audit
                  else
                    echo '__PLUGIN_UNAVAILABLE__'
                  fi
                else
                  echo '__PLUGIN_NOT_APPLICABLE__'
                fi
            """.trimIndent(),
            parser = { interpretGeneral(it, "Rust dependency audit reported issues.", "Address the reported Rust advisories before implementing this path.") },
        ),
    )

    private fun interpretGitleaks(result: CommandResult): ScanResult {
        val output = result.combinedOutput()
        return when {
            output.contains("__PLUGIN_UNAVAILABLE__") -> unavailable("gitleaks")
            result.exitCode == 0 -> info("gitleaks", "No secrets detected in the local clone.", "Proceed, but keep secrets out of tracked files.")
            else -> ScanResult(
                toolName = "gitleaks",
                statusLabel = "High Risk",
                findings = truncate(output.ifBlank { "Potential secrets were detected." }),
                recommendation = "Remove exposed secrets and rotate any real credentials before implementing this recommendation.",
                severity = ScanSeverity.HIGH_RISK,
            )
        }
    }

    private fun interpretHeuristics(result: CommandResult): ScanResult {
        val output = result.combinedOutput()
        return when {
            output.contains("__PLUGIN_UNAVAILABLE__") -> unavailable("heuristics")
            result.exitCode == 1 && result.stderr.isBlank() -> info("heuristics", "No risky command or secret-like patterns matched the heuristic scan.", "Continue, but still review any installation scripts manually.")
            result.exitCode == 0 && output.isNotBlank() -> ScanResult(
                toolName = "heuristics",
                statusLabel = "Warning",
                findings = truncate(output),
                recommendation = "Review the matched files before porting this approach into the main repository.",
                severity = ScanSeverity.WARNING,
            )
            else -> ScanResult(
                toolName = "heuristics",
                statusLabel = "Failed",
                findings = truncate(output.ifBlank { "Heuristic scan failed." }),
                recommendation = "Inspect the heuristic scan failure before trusting the sandbox result.",
                severity = ScanSeverity.HIGH_RISK,
            )
        }
    }

    private fun interpretGeneral(
        result: CommandResult,
        findingOnIssues: String,
        recommendationOnIssues: String,
    ): ScanResult {
        val output = result.combinedOutput()
        return when {
            output.contains("__PLUGIN_NOT_APPLICABLE__") -> notApplicable()
            output.contains("__PLUGIN_UNAVAILABLE__") -> unavailable("scan tool")
            result.exitCode == 0 -> info("scan", truncate(output.ifBlank { "No issues reported." }), "No blocking findings were reported by this scanner.")
            else -> {
                val severity = inferSeverity(output)
                ScanResult(
                    toolName = "scan",
                    statusLabel = if (severity == ScanSeverity.HIGH_RISK) "High Risk" else "Warning",
                    findings = truncate(output.ifBlank { findingOnIssues }),
                    recommendation = recommendationOnIssues,
                    severity = severity,
                )
            }
        }
    }

    private fun unavailable(toolName: String): ScanResult =
        ScanResult(
            toolName = toolName,
            statusLabel = "Unavailable",
            findings = "The scanner is not installed in the sandbox environment.",
            recommendation = "Install the scanner in the codespace if this check is required for the workflow.",
            severity = null,
        )

    private fun notApplicable(): ScanResult =
        ScanResult(
            toolName = "scan",
            statusLabel = "Not applicable",
            findings = "The repository does not contain files for this ecosystem.",
            recommendation = "No action needed for this scanner.",
            severity = null,
        )

    private fun info(
        toolName: String,
        findings: String,
        recommendation: String,
    ): ScanResult = ScanResult(
        toolName = toolName,
        statusLabel = "Info",
        findings = findings,
        recommendation = recommendation,
        severity = ScanSeverity.INFO,
    )

    private fun inferSeverity(output: String): ScanSeverity {
        val lower = output.lowercase()
        return when {
            lower.contains("critical") || lower.contains("secret") || lower.contains("leak") -> ScanSeverity.HIGH_RISK
            else -> ScanSeverity.WARNING
        }
    }

    private fun truncate(output: String, maxLines: Int = 8): String =
        output.lineSequence().filter { it.isNotBlank() }.take(maxLines).joinToString("<br>")
}

private data class ScanSpec(
    val toolName: String,
    val command: String,
    val parser: (CommandResult) -> ScanResult,
) {
    fun runningResult(): ScanResult = ScanResult(
        toolName = toolName,
        statusLabel = "Running",
        findings = "Executing the scanner inside the sandbox.",
        recommendation = "Wait for the command to complete.",
        severity = null,
    )

    fun interpret(result: CommandResult): ScanResult {
        val base = parser(result)
        return base.copy(toolName = toolName)
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
