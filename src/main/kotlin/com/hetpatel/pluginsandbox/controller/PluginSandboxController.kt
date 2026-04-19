package com.hetpatel.pluginsandbox.controller

import com.hetpatel.pluginsandbox.model.PluginSandboxState
import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.SandboxConfig
import com.hetpatel.pluginsandbox.model.SandboxSession
import com.hetpatel.pluginsandbox.model.SandboxStatus
import com.hetpatel.pluginsandbox.services.GitHubCodespacesService
import com.hetpatel.pluginsandbox.services.ImplementationPromptGenerator
import com.hetpatel.pluginsandbox.services.McpBridgeManager
import com.hetpatel.pluginsandbox.services.RecommendationEngine
import com.hetpatel.pluginsandbox.services.ScanOrchestrator
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

class PluginSandboxController(
    repoRoot: java.nio.file.Path?,
) : Disposable {
    private val listeners = CopyOnWriteArrayList<(PluginSandboxState) -> Unit>()
    private val recommendationEngine = RecommendationEngine()
    private val codespacesService = GitHubCodespacesService(repoRoot)
    private val scanOrchestrator = ScanOrchestrator(codespacesService)
    private val mcpBridgeManager = McpBridgeManager(repoRoot)
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private var activeSandboxTask: Future<*>? = null
    private var state = PluginSandboxState(
        mcpBridge = mcpBridgeManager.info(),
    )

    fun subscribe(listener: (PluginSandboxState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun updatePrd(prd: String) {
        setState(
            state.copy(
                prdText = prd,
            ),
        )
    }

    fun updateSandboxConfig(config: SandboxConfig) {
        setState(
            state.copy(
                sandboxConfig = config,
            ),
        )
    }

    fun generateRecommendations() {
        val recommendations = recommendationEngine.generate(state.prdText)
        activeSandboxTask?.cancel(true)
        setState(
            state.copy(
                recommendations = recommendations,
                selectedRecommendationId = null,
                sandboxSession = null,
                mcpBridge = mcpBridgeManager.info("Launch `Try it out` to bind AI chat tools to a live sandbox."),
                scanResults = emptyList(),
                generatedPrompt = "",
                lastError = "",
            ),
        )
    }

    fun tryRecommendation(recommendationId: String) {
        val recommendation = state.recommendations.firstOrNull { it.id == recommendationId } ?: return
        activeSandboxTask?.cancel(true)

        setState(
            state.copy(
                selectedRecommendationId = recommendation.id,
                sandboxSession = SandboxSession(
                    codespaceName = "",
                    recommendationTitle = recommendation.title,
                    status = SandboxStatus.LAUNCHING,
                    summary = "Preparing a launcher Codespace for ${recommendation.title}. The launcher environment will clone and boot the target repo automatically inside /workspaces/target.",
                    commandPreview = codespacesService.commandPreview(state.sandboxConfig, recommendation),
                ),
                generatedPrompt = "",
                scanResults = emptyList(),
                lastError = "",
            ),
        )

        log("Launching sandbox workflow for ${recommendation.title}.")
        activeSandboxTask = scheduler.submit {
            runCatching {
                val details = codespacesService.ensureCodespace(state.sandboxConfig, recommendation, ::log)
                ApplicationManager.getApplication().invokeLater {
                    val current = state
                    if (current.selectedRecommendationId == recommendation.id) {
                        setState(
                            current.copy(
                                sandboxSession = SandboxSession(
                                    codespaceName = details.name,
                                    recommendationTitle = recommendation.title,
                                    status = SandboxStatus.READY,
                                    summary = buildString {
                                        append("Codespace ${details.name} is ready")
                                        if (details.machineDisplayName.isNotBlank()) {
                                            append(" on ${details.machineDisplayName}")
                                        }
                                        if (details.location.isNotBlank()) {
                                            append(" in ${details.location}")
                                        }
                                        append(". ")
                                        append(details.bootstrapSummary.ifBlank {
                                            "Validate the affected areas first: ${recommendation.affectedAreas.joinToString(", ")}."
                                        })
                                    },
                                    commandPreview = "Open browser sandbox for ${details.name}",
                                    browserUrl = details.webUrl,
                                ),
                                mcpBridge = mcpBridgeManager.info("AI chat MCP bridge is bound to ${details.name}."),
                                lastError = "",
                            ),
                        )
                    }
                }
                mcpBridgeManager.writeActiveSandbox(
                    codespaceName = details.name,
                    repositoryName = "target",
                    branch = details.branch,
                    browserUrl = details.webUrl,
                    recommendation = recommendation,
                    workspacePath = "/workspaces/target",
                )
                scanOrchestrator.startScan(
                    codespaceName = details.name,
                    workspacePath = "/workspaces/target",
                    recommendation = recommendation,
                    onUpdate = { scanResults ->
                        setState(state.copy(scanResults = scanResults))
                    },
                    onLog = ::log,
                )
            }.onFailure { error ->
                ApplicationManager.getApplication().invokeLater {
                    val current = state
                    if (current.selectedRecommendationId == recommendation.id) {
                        setState(
                            current.copy(
                                sandboxSession = current.sandboxSession?.copy(
                                    status = SandboxStatus.ERROR,
                                    summary = error.message ?: "Failed to prepare the sandbox.",
                                ),
                                lastError = error.message ?: "Failed to prepare the sandbox.",
                            ),
                        )
                    }
                }
                log(error.message ?: "Failed to prepare the sandbox.")
            }
        }
    }

    fun implementRecommendation(recommendationId: String) {
        val recommendation = state.recommendations.firstOrNull { it.id == recommendationId } ?: return
        val prompt = ImplementationPromptGenerator().generate(state, recommendation)
        setState(
            state.copy(
                selectedRecommendationId = recommendation.id,
                generatedPrompt = prompt,
            ),
        )
    }

    fun discardRecommendation(recommendationId: String) {
        val remaining = state.recommendations.filterNot { it.id == recommendationId }
        val clearSelection = state.selectedRecommendationId == recommendationId
        if (clearSelection) {
            mcpBridgeManager.clearActiveSandbox()
        }
        setState(
            state.copy(
                recommendations = remaining,
                selectedRecommendationId = if (clearSelection) null else state.selectedRecommendationId,
                sandboxSession = if (clearSelection) null else state.sandboxSession,
                mcpBridge = if (clearSelection) mcpBridgeManager.info() else state.mcpBridge,
                scanResults = if (clearSelection) emptyList() else state.scanResults,
                generatedPrompt = if (clearSelection) "" else state.generatedPrompt,
            ),
        )
    }

    override fun dispose() {
        activeSandboxTask?.cancel(true)
        scanOrchestrator.dispose()
        mcpBridgeManager.clearActiveSandbox()
        listeners.clear()
    }

    private fun setState(nextState: PluginSandboxState) {
        state = nextState
        listeners.forEach { listener -> listener(state) }
    }

    private fun log(message: String) {
        val formatted = "[${java.time.LocalTime.now().withNano(0)}] $message"
        if (ApplicationManager.getApplication().isDispatchThread) {
            appendLog(formatted)
        } else {
            ApplicationManager.getApplication().invokeLater {
                appendLog(formatted)
            }
        }
    }

    private fun appendLog(message: String) {
        val nextLog = (state.activityLog + message).takeLast(40)
        setState(state.copy(activityLog = nextLog))
    }

}
