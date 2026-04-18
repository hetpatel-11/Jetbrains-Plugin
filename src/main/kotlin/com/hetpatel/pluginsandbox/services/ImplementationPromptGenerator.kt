package com.hetpatel.pluginsandbox.services

import com.hetpatel.pluginsandbox.model.PluginSandboxState
import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.ScanResult
import java.util.Locale

class ImplementationPromptGenerator {
    fun generate(state: PluginSandboxState, recommendation: Recommendation): String {
        val sandboxSummary = state.sandboxSession?.summary ?: "Sandbox has not been launched yet. Validate the main flow before landing code."
        val scanSummary = summarizeScans(state.scanResults)
        val affectedAreas = inferAffectedAreas(state.prdText)

        return buildString {
            appendLine("Implement the selected recommendation in the current repository.")
            appendLine()
            appendLine("Selected recommendation:")
            appendLine(recommendation.title)
            appendLine("Reference repository: ${recommendation.repositoryUrl}")
            appendLine()
            appendLine("Why it was chosen:")
            appendLine(recommendation.whenToChoose)
            appendLine()
            appendLine("What was validated in sandbox:")
            appendLine(sandboxSummary)
            appendLine()
            appendLine("Security scan summary:")
            appendLine(scanSummary)
            appendLine()
            appendLine("Expected outcome:")
            appendLine(recommendation.summary)
            appendLine()
            appendLine("Acceptance criteria:")
            appendLine("- Preserve the core workflow described in the PRD.")
            appendLine("- Keep the implementation scoped so it can be reviewed incrementally.")
            appendLine("- Surface any risk or follow-up work that remains after the first pass.")
            appendLine()
            appendLine("Constraints:")
            appendLine("- Keep changes scoped.")
            appendLine("- Do not break existing behavior.")
            appendLine("- Reuse existing patterns where possible.")
            appendLine()
            appendLine("Likely affected areas:")
            affectedAreas.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Original PRD / idea:")
            appendLine(state.prdText.ifBlank { "No PRD provided." })
            appendLine()
            appendLine("Please implement the change, explain the edits, and note any follow-up work.")
        }
    }

    private fun summarizeScans(scanResults: List<ScanResult>): String {
        if (scanResults.isEmpty()) {
            return "No scan results available yet."
        }

        return scanResults.joinToString("\n") { scan ->
            "- ${scan.toolName}: ${scan.statusLabel}. ${scan.findings}. ${scan.recommendation}"
        }
    }

    private fun inferAffectedAreas(prd: String): List<String> {
        val lower = prd.lowercase(Locale.getDefault())
        val areas = linkedSetOf<String>()

        if (lower.contains("ui") || lower.contains("screen") || lower.contains("page")) {
            areas += "UI components and presentation state"
        }
        if (lower.contains("api") || lower.contains("endpoint")) {
            areas += "API handlers and request/response contracts"
        }
        if (lower.contains("auth") || lower.contains("permission")) {
            areas += "Authentication and authorization flows"
        }
        if (lower.contains("data") || lower.contains("database") || lower.contains("sync")) {
            areas += "Persistence layer and data model mapping"
        }
        if (areas.isEmpty()) {
            areas += "Primary feature module tied to the requested workflow"
            areas += "Shared integration points affected by the selected recommendation"
        }

        return areas.toList()
    }
}
