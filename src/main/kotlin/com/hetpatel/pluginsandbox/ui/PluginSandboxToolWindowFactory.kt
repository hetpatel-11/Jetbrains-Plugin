package com.hetpatel.pluginsandbox.ui

import com.hetpatel.pluginsandbox.controller.PluginSandboxController
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.nio.file.Paths
import java.awt.BorderLayout
import javax.swing.JPanel

class PluginSandboxToolWindowFactory : ToolWindowFactory, DumbAware {
    private val logger = Logger.getInstance(PluginSandboxToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = runCatching {
            val controller = PluginSandboxController(project.basePath?.let(Paths::get))
            val panel = PluginSandboxPanel(project, controller)
            ContentFactory.getInstance().createContent(panel, "", false).also {
                it.setDisposer(controller)
            }
        }.getOrElse { error ->
            logger.error("Failed to initialize Plugin Sandbox tool window", error)
            ContentFactory.getInstance().createContent(errorPanel(error), "", false)
        }
        toolWindow.contentManager.addContent(content)
    }

    private fun errorPanel(error: Throwable): JPanel {
        val text = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(12)
            text = buildString {
                appendLine("Plugin Sandbox failed to initialize.")
                appendLine()
                appendLine(error::class.java.name)
                if (!error.message.isNullOrBlank()) {
                    appendLine(error.message)
                }
                appendLine()
                appendLine("Reinstall the latest ZIP if this persists.")
            }
        }
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(text), BorderLayout.CENTER)
        }
    }
}
