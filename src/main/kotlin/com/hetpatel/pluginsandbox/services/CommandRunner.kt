package com.hetpatel.pluginsandbox.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.charset.StandardCharsets
import java.time.Duration

class CommandRunner {
    fun run(
        command: List<String>,
        timeout: Duration = Duration.ofMinutes(10),
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        val commandLine = GeneralCommandLine(command).withCharset(StandardCharsets.UTF_8)
        if (environment.isNotEmpty()) {
            commandLine.withEnvironment(environment)
        }
        val output = CapturingProcessHandler(commandLine).runProcess(timeout.toMillis().toInt())
        return CommandResult(
            command = command,
            exitCode = output.exitCode,
            stdout = output.stdout.trim(),
            stderr = output.stderr.trim(),
            timedOut = output.isTimeout,
        )
    }
}

data class CommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    fun combinedOutput(): String =
        listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    fun ensureSuccess(message: String): CommandResult {
        if (timedOut || exitCode != 0) {
            throw IllegalStateException(
                buildString {
                    append(message)
                    val output = combinedOutput()
                    if (output.isNotBlank()) {
                        append("\n")
                        append(output)
                    }
                },
            )
        }
        return this
    }
}
