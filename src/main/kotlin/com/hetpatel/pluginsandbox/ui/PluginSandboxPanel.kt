package com.hetpatel.pluginsandbox.ui

import com.hetpatel.pluginsandbox.controller.PluginSandboxController
import com.hetpatel.pluginsandbox.model.PluginSandboxState
import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.RecommendationKind
import com.hetpatel.pluginsandbox.model.RiskLevel
import com.hetpatel.pluginsandbox.model.SandboxConfig
import com.hetpatel.pluginsandbox.model.SandboxStatus
import com.hetpatel.pluginsandbox.model.ScanResult
import com.hetpatel.pluginsandbox.model.ScanSeverity
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.JComponent

class PluginSandboxPanel(
    project: Project,
    private val controller: PluginSandboxController,
) : JPanel(BorderLayout()) {
    private var syncingConfigFields = false
    private val prdInput = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 6
        border = JBUI.Borders.empty(8)
        text = "Paste a PRD, issue, or product idea here."
    }
    private val branchField = JTextField("main")
    private val devcontainerField = JTextField()
    private val machineField = JTextField()
    private val recommendationsPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val sandboxStatusLabel = JBLabel("Sandbox idle")
    private val sandboxSummaryLabel = JBLabel("Choose `Try it out` on a recommendation to launch its sandbox workflow.")
    private val sandboxCommandLabel = JBLabel("No sandbox command prepared.")
    private val sandboxErrorLabel = JBLabel("")
    private val sandboxBrowserPanel = JPanel(BorderLayout())
    private var browserUrl: String = ""
    private var browser: JBCefBrowser? = null
    private val activityLogArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 8
        border = JBUI.Borders.empty(8)
    }
    private val scansPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val mcpStatusLabel = JBLabel("Launch `Try it out` to bind AI chat tools to a live sandbox.")
    private val mcpConfigArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 8
        border = JBUI.Borders.empty(8)
    }
    private val promptArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 12
        border = JBUI.Borders.empty(8)
    }

    init {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            add(section("Product Idea", buildInputSection()))
            add(Box.createVerticalStrut(12))
            add(section("GitHub Sandbox", buildConfigSection()))
            add(Box.createVerticalStrut(12))
            add(section("Recommendations", recommendationsPanel))
            add(Box.createVerticalStrut(12))
            add(section("Sandbox", buildSandboxSection()))
            add(Box.createVerticalStrut(12))
            add(section("Scan Status", scansPanel))
            add(Box.createVerticalStrut(12))
            add(section("AI MCP Bridge", buildMcpSection()))
            add(Box.createVerticalStrut(12))
            add(section("Implementation Prompt", buildPromptSection()))
        }

        add(JBScrollPane(container), BorderLayout.CENTER)

        controller.subscribe(::render)
        prdInput.text = ""
        branchField.text = detectBranch(project)
        installConfigListeners()
        controller.updatePrd("")
        controller.updateSandboxConfig(currentConfig())
        if (project.basePath != null) {
            sandboxCommandLabel.toolTipText = project.basePath
        }
    }

    private fun buildInputSection(): JPanel {
        val generateButton = JButton("Generate 3 Recommendations").apply {
            addActionListener {
                controller.updatePrd(prdInput.text)
                controller.generateRecommendations()
            }
        }

        return JPanel(BorderLayout(0, 8)).apply {
            alignmentX = LEFT_ALIGNMENT
            add(JBScrollPane(prdInput).apply { preferredSize = Dimension(0, 130) }, BorderLayout.CENTER)
            add(generateButton, BorderLayout.SOUTH)
        }
    }

    private fun buildConfigSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(labeledField("Branch", branchField))
            add(Box.createVerticalStrut(6))
            add(labeledField("Devcontainer Path", devcontainerField))
            add(Box.createVerticalStrut(6))
            add(labeledField("Machine", machineField))
            add(Box.createVerticalStrut(6))
            add(
                JBLabel(
                    "<html>`Try it out` creates a Codespace from this launcher repo. The selected recommendation determines which target repo the launcher clones into `/workspaces/target`. Branch/devcontainer/machine are launcher overrides.</html>",
                ).apply {
                    foreground = JBColor.GRAY
                },
            )
        }
    }

    private fun buildSandboxSection(): JPanel {
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(JButton("Expand Sandbox").apply {
                addActionListener {
                    if (browserUrl.isNotBlank()) {
                        BrowserUtil.browse(browserUrl)
                    }
                }
            })
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(sandboxStatusLabel)
            add(Box.createVerticalStrut(6))
            add(sandboxSummaryLabel)
            add(Box.createVerticalStrut(6))
            add(sandboxCommandLabel)
            add(Box.createVerticalStrut(6))
            add(sandboxErrorLabel)
            add(Box.createVerticalStrut(6))
            add(controls)
            add(Box.createVerticalStrut(8))
            add(sandboxBrowserPanel.apply { preferredSize = Dimension(0, 480) })
            add(Box.createVerticalStrut(8))
            add(JBScrollPane(activityLogArea).apply { preferredSize = Dimension(0, 180) })
        }
    }

    private fun buildPromptSection(): JPanel {
        val copyButton = JButton("Copy Prompt").apply {
            addActionListener {
                val text = promptArea.text
                if (text.isNotBlank()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                    Toolkit.getDefaultToolkit().beep()
                }
            }
        }

        return JPanel(BorderLayout(0, 8)).apply {
            alignmentX = LEFT_ALIGNMENT
            add(JBScrollPane(promptArea).apply { preferredSize = Dimension(0, 240) }, BorderLayout.CENTER)
            add(copyButton, BorderLayout.SOUTH)
        }
    }

    private fun buildMcpSection(): JPanel {
        val copyConfigButton = JButton("Copy MCP Config").apply {
            addActionListener {
                if (mcpConfigArea.text.isNotBlank()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(mcpConfigArea.text))
                    Toolkit.getDefaultToolkit().beep()
                }
            }
        }

        return JPanel(BorderLayout(0, 8)).apply {
            alignmentX = LEFT_ALIGNMENT
            add(mcpStatusLabel, BorderLayout.NORTH)
            add(JBScrollPane(mcpConfigArea).apply { preferredSize = Dimension(0, 170) }, BorderLayout.CENTER)
            add(copyConfigButton, BorderLayout.SOUTH)
        }
    }

    private fun render(state: PluginSandboxState) {
        renderRecommendations(state)
        renderSandbox(state)
        renderScans(state.scanResults)
        renderMcpBridge(state)
        promptArea.text = state.generatedPrompt.ifBlank {
            "Select `Implement` on a recommendation after reviewing the sandbox and scan summary."
        }
        syncingConfigFields = true
        try {
            if (branchField.text != state.sandboxConfig.branch) branchField.text = state.sandboxConfig.branch
            if (devcontainerField.text != state.sandboxConfig.devcontainerPath) devcontainerField.text = state.sandboxConfig.devcontainerPath
            if (machineField.text != state.sandboxConfig.machine) machineField.text = state.sandboxConfig.machine
        } finally {
            syncingConfigFields = false
        }
        revalidate()
        repaint()
    }

    private fun renderRecommendations(state: PluginSandboxState) {
        recommendationsPanel.removeAll()

        if (state.recommendations.isEmpty()) {
            recommendationsPanel.add(mutedLabel("Generate recommendations to see the top 3 options for this PRD."))
            return
        }

        state.recommendations.forEach { recommendation ->
            recommendationsPanel.add(
                createRecommendationCard(
                    recommendation = recommendation,
                    isSelected = state.selectedRecommendationId == recommendation.id,
                    onTry = { controller.tryRecommendation(recommendation.id) },
                    onImplement = { controller.implementRecommendation(recommendation.id) },
                    onDiscard = { controller.discardRecommendation(recommendation.id) },
                ),
            )
            recommendationsPanel.add(Box.createVerticalStrut(10))
        }
    }

    private fun renderSandbox(state: PluginSandboxState) {
        val session = state.sandboxSession
        if (session == null) {
            sandboxStatusLabel.text = "Sandbox idle"
            sandboxSummaryLabel.text = "Choose `Try it out` on a recommendation to launch its sandbox workflow."
            sandboxCommandLabel.text = "No sandbox command prepared."
            sandboxErrorLabel.text = state.lastError
            activityLogArea.text = state.activityLog.joinToString("\n")
            updateBrowser("")
            return
        }

        sandboxStatusLabel.text = when (session.status) {
            SandboxStatus.LAUNCHING -> "Launching sandbox for ${session.recommendationTitle}"
            SandboxStatus.READY -> "Sandbox ready for ${session.recommendationTitle}"
            SandboxStatus.ERROR -> "Sandbox failed for ${session.recommendationTitle}"
        }
        sandboxSummaryLabel.text = session.summary
        sandboxCommandLabel.text = buildString {
            append("Command preview: ${session.commandPreview}")
            if (session.codespaceName.isNotBlank()) {
                append(" | Codespace: ${session.codespaceName}")
            }
        }
        sandboxErrorLabel.text = state.lastError
        sandboxErrorLabel.foreground = if (state.lastError.isBlank()) JBColor.GRAY else JBColor.RED
        activityLogArea.text = state.activityLog.joinToString("\n")
        updateBrowser(session.browserUrl)
    }

    private fun renderScans(scanResults: List<ScanResult>) {
        scansPanel.removeAll()

        if (scanResults.isEmpty()) {
            scansPanel.add(mutedLabel("Security and vulnerability results will appear here after `Try it out`."))
            return
        }

        scanResults.forEach { result ->
            scansPanel.add(createScanRow(result))
            scansPanel.add(Box.createVerticalStrut(8))
        }
    }

    private fun renderMcpBridge(state: PluginSandboxState) {
        mcpStatusLabel.text =
            "<html>${state.mcpBridge.status}<br><br>Paste this JSON into `Settings | Tools | AI Assistant | Model Context Protocol (MCP)` and AI chat will get sandbox tools bound to the active codespace.</html>"
        mcpConfigArea.text = state.mcpBridge.configJson
        mcpConfigArea.toolTipText = state.mcpBridge.stateFilePath
    }

    private fun createRecommendationCard(
        recommendation: Recommendation,
        isSelected: Boolean,
        onTry: ActionListener,
        onImplement: ActionListener,
        onDiscard: ActionListener,
    ): JPanel {
        val title = JBLabel(recommendation.title).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1)
        }
        val kind = JBLabel("Track: ${recommendation.kind.displayName()}")
        val meta = JBLabel(
            "Complexity: ${recommendation.complexity} | Risk: ${recommendation.riskLevel.displayName()} | ${recommendation.whenToChoose}",
        )
        val summary = JBLabel("<html>${recommendation.summary}</html>")
        val tradeoffs = JBLabel("<html><b>Tradeoffs:</b> ${recommendation.tradeoffs}</html>")
        val validationPlan = JBLabel("<html><b>Sandbox plan:</b> ${recommendation.validationPlan}</html>")
        val affectedAreas = JBLabel("<html><b>Affected areas:</b> ${recommendation.affectedAreas.joinToString(", ")}</html>")
        val repo = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JBLabel("Repository: ${recommendation.repositoryUrl}"))
            add(Box.createHorizontalStrut(8))
            add(JButton("Open Repo").apply {
                addActionListener {
                    BrowserUtil.browse(recommendation.repositoryUrl)
                }
            })
        }
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JButton("Try it out").apply { addActionListener(onTry) })
            add(Box.createHorizontalStrut(8))
            add(JButton("Implement").apply { addActionListener(onImplement) })
            add(Box.createHorizontalStrut(8))
            add(JButton("Discard").apply { addActionListener(onDiscard) })
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (isSelected) JBColor(0x4C7DFF, 0x7AA2FF) else JBColor.border()),
                JBUI.Borders.empty(10),
            )
            background = if (isSelected) JBColor(Color(239, 244, 255), Color(47, 52, 64)) else null
            add(title)
            add(Box.createVerticalStrut(4))
            add(kind)
            add(Box.createVerticalStrut(4))
            add(meta)
            add(Box.createVerticalStrut(6))
            add(summary)
            add(Box.createVerticalStrut(6))
            add(tradeoffs)
            add(Box.createVerticalStrut(6))
            add(validationPlan)
            add(Box.createVerticalStrut(6))
            add(affectedAreas)
            add(Box.createVerticalStrut(6))
            add(repo)
            add(Box.createVerticalStrut(8))
            add(actions)
        }
    }

    private fun createScanRow(result: ScanResult): JPanel {
        val icon = when (result.severity) {
            ScanSeverity.INFO -> AllIcons.General.Information
            ScanSeverity.WARNING -> AllIcons.General.Warning
            ScanSeverity.HIGH_RISK -> AllIcons.General.Error
            null -> AllIcons.Process.Step_passive
        }

        return JPanel(BorderLayout(8, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8),
            )
            add(JBLabel(icon), BorderLayout.WEST)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JBLabel("${result.toolName}: ${result.statusLabel}"))
                    add(Box.createVerticalStrut(4))
                    add(JBLabel("<html>${result.findings}</html>"))
                    add(Box.createVerticalStrut(2))
                    add(JBLabel("<html><b>Recommendation:</b> ${result.recommendation}</html>"))
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun section(title: String, content: JPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1)
                border = JBUI.Borders.emptyBottom(8)
            })
            add(content)
        }
    }

    private fun mutedLabel(text: String): JBLabel =
        JBLabel("<html>$text</html>").apply {
            foreground = JBColor.GRAY
        }

    private fun labeledField(
        label: String,
        field: JTextField,
    ): JPanel = JPanel(BorderLayout(0, 4)).apply {
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel(label), BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun installConfigListeners() {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = push()
            override fun removeUpdate(e: DocumentEvent?) = push()
            override fun changedUpdate(e: DocumentEvent?) = push()

            private fun push() {
                if (syncingConfigFields) {
                    return
                }
                controller.updateSandboxConfig(currentConfig())
            }
        }
        listOf(branchField, devcontainerField, machineField).forEach {
            it.document.addDocumentListener(listener)
        }
    }

    private fun currentConfig(): SandboxConfig = SandboxConfig(
        branch = branchField.text.trim().ifBlank { "main" },
        devcontainerPath = devcontainerField.text.trim(),
        machine = machineField.text.trim(),
    )

    private fun detectBranch(project: Project): String =
        project.basePath?.let {
            runCatching {
                ProcessBuilder("git", "-C", it, "branch", "--show-current")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }.getOrDefault("")
        }.orEmpty().ifBlank { "main" }

    private fun updateBrowser(url: String) {
        if (browserUrl == url) {
            return
        }
        browserUrl = url
        sandboxBrowserPanel.removeAll()
        browser?.dispose()
        browser = null

        val component: JComponent = when {
            url.isBlank() -> mutedLabel("The live Codespace UI will appear here after the sandbox becomes available.")
            url.contains("github.com", ignoreCase = true) || url.contains("github.dev", ignoreCase = true) ->
                mutedLabel("Embedded GitHub Codespaces is disabled in-plugin because the IDE browser does not share your normal GitHub session. Use `Expand Sandbox` to open it in your signed-in browser.")
            !JBCefApp.isSupported() -> mutedLabel("JCEF is unavailable in this IDE runtime, so the sandbox can’t be embedded here. Use the codespace URL externally.")
            else -> {
                browser = JBCefBrowser(url)
                browser!!.component
            }
        }
        sandboxBrowserPanel.add(component, BorderLayout.CENTER)
        sandboxBrowserPanel.revalidate()
        sandboxBrowserPanel.repaint()
    }
    private fun RiskLevel.displayName(): String = when (this) {
        RiskLevel.LOW -> "Low"
        RiskLevel.MEDIUM -> "Medium"
        RiskLevel.HIGH -> "High"
    }

    private fun RecommendationKind.displayName(): String = when (this) {
        RecommendationKind.FAST_API_SIDECAR -> "FastAPI reference"
        RecommendationKind.DIRECT_IN_REPO -> "Flask reference"
        RecommendationKind.ADAPTER_SANDBOX -> "Django reference"
    }
}
