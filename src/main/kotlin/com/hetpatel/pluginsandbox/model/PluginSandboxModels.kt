package com.hetpatel.pluginsandbox.model

data class PluginSandboxState(
    val prdText: String = "",
    val sandboxConfig: SandboxConfig = SandboxConfig(),
    val recommendations: List<Recommendation> = emptyList(),
    val selectedRecommendationId: String? = null,
    val sandboxSession: SandboxSession? = null,
    val scanResults: List<ScanResult> = emptyList(),
    val generatedPrompt: String = "",
    val activityLog: List<String> = emptyList(),
    val lastError: String = "",
)

data class Recommendation(
    val id: String,
    val kind: RecommendationKind,
    val title: String,
    val summary: String,
    val tradeoffs: String,
    val riskLevel: RiskLevel,
    val complexity: String,
    val whenToChoose: String,
    val validationPlan: String,
    val affectedAreas: List<String>,
    val repositoryUrl: String,
    val repositorySlug: String,
)

enum class RecommendationKind {
    FAST_API_SIDECAR,
    DIRECT_IN_REPO,
    ADAPTER_SANDBOX,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class SandboxSession(
    val codespaceName: String = "",
    val recommendationTitle: String,
    val status: SandboxStatus,
    val summary: String,
    val commandPreview: String,
    val browserUrl: String = "",
)

enum class SandboxStatus {
    LAUNCHING,
    READY,
    ERROR,
}

data class ScanResult(
    val toolName: String,
    val statusLabel: String,
    val findings: String,
    val recommendation: String,
    val severity: ScanSeverity?,
)

enum class ScanSeverity {
    INFO,
    WARNING,
    HIGH_RISK,
}

data class SandboxConfig(
    val owner: String = "",
    val repository: String = "",
    val branch: String = "main",
    val devcontainerPath: String = "",
    val machine: String = "",
)
