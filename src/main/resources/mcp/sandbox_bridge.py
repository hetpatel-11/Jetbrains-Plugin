#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path


SERVER_NAME = "plugin-sandbox"
SERVER_VERSION = "0.1.0"


def read_message():
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        if line in (b"\r\n", b"\n"):
            break
        key, value = line.decode("utf-8").split(":", 1)
        headers[key.strip().lower()] = value.strip()
    length = int(headers.get("content-length", "0"))
    if length <= 0:
        return None
    payload = sys.stdin.buffer.read(length)
    if not payload:
        return None
    return json.loads(payload.decode("utf-8"))


def write_message(message):
    encoded = json.dumps(message, separators=(",", ":")).encode("utf-8")
    sys.stdout.buffer.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("utf-8"))
    sys.stdout.buffer.write(encoded)
    sys.stdout.buffer.flush()


def tool_descriptor(name, description, schema):
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
    }


class SandboxBridge:
    def __init__(self, state_file):
        self.state_file = Path(state_file)

    def read_state(self):
        if not self.state_file.exists():
            raise RuntimeError(
                f"No active sandbox state file found at {self.state_file}. Launch `Try it out` in the plugin first."
            )
        return json.loads(self.state_file.read_text())

    def codespace_ssh_config(self, codespace_name):
        result = subprocess.run(
            ["gh", "codespace", "ssh", "-c", codespace_name, "--config"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "Failed to fetch codespace SSH config.")
        host_match = re.search(r"^Host\s+(.+)$", result.stdout, re.MULTILINE)
        if not host_match:
            raise RuntimeError("Failed to resolve codespace SSH host alias.")
        return host_match.group(1).strip(), result.stdout

    def remote_exec(self, command, cwd=None, timeout=600):
        state = self.read_state()
        host, config_text = self.codespace_ssh_config(state["codespaceName"])
        command_body = command.strip()
        remote_command = command_body if not cwd else f"cd {shell_quote(cwd)}\n{command_body}"
        with tempfile.NamedTemporaryFile("w", delete=False) as handle:
            handle.write(config_text)
            config_path = handle.name
        try:
            result = subprocess.run(
                ["ssh", "-F", config_path, host, "bash", "-lc", remote_command],
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        finally:
            try:
                os.unlink(config_path)
            except OSError:
                pass
        return {
            "exitCode": result.returncode,
            "stdout": result.stdout.strip(),
            "stderr": result.stderr.strip(),
            "workspace": state.get("workspacePath") or f"/workspaces/{state['repositoryName']}",
            "codespaceName": state["codespaceName"],
            "repositorySlug": state["repositorySlug"],
        }

    def tools(self):
        return [
            tool_descriptor(
                "sandbox_status",
                "Show the active sandbox the plugin has bound this MCP bridge to.",
                {"type": "object", "properties": {}, "additionalProperties": False},
            ),
            tool_descriptor(
                "sandbox_exec",
                "Run a shell command inside the active GitHub Codespace sandbox.",
                {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "cwd": {"type": "string"},
                        "timeout_seconds": {"type": "integer", "minimum": 1, "maximum": 3600},
                    },
                    "required": ["command"],
                    "additionalProperties": False,
                },
            ),
            tool_descriptor(
                "sandbox_grep",
                "Run ripgrep inside the active sandbox.",
                {
                    "type": "object",
                    "properties": {
                        "pattern": {"type": "string"},
                        "path": {"type": "string"},
                        "timeout_seconds": {"type": "integer", "minimum": 1, "maximum": 3600},
                    },
                    "required": ["pattern"],
                    "additionalProperties": False,
                },
            ),
            tool_descriptor(
                "sandbox_install_security_tools",
                "Install gitleaks, trivy, and osv-scanner into the active sandbox using official upstream sources.",
                {
                    "type": "object",
                    "properties": {
                        "tools": {
                            "type": "array",
                            "items": {"type": "string", "enum": ["gitleaks", "trivy", "osv-scanner"]},
                        }
                    },
                    "additionalProperties": False,
                },
            ),
            tool_descriptor(
                "sandbox_run_vulnerability_suite",
                "Run vulnerability and heuristic checks inside the active sandbox.",
                {
                    "type": "object",
                    "properties": {
                        "install_missing": {"type": "boolean"},
                        "timeout_seconds": {"type": "integer", "minimum": 1, "maximum": 3600},
                    },
                    "additionalProperties": False,
                },
            ),
        ]

    def handle_tool_call(self, name, arguments):
        arguments = arguments or {}
        if name == "sandbox_status":
            state = self.read_state()
            return tool_text(json.dumps(state, indent=2))

        if name == "sandbox_exec":
            state = self.read_state()
            cwd = arguments.get("cwd") or state.get("workspacePath") or f"/workspaces/{state['repositoryName']}"
            timeout = int(arguments.get("timeout_seconds") or 600)
            result = self.remote_exec(arguments["command"], cwd=cwd, timeout=timeout)
            return tool_text(render_command_result(result))

        if name == "sandbox_grep":
            state = self.read_state()
            cwd = state.get("workspacePath") or f"/workspaces/{state['repositoryName']}"
            search_path = arguments.get("path") or "."
            timeout = int(arguments.get("timeout_seconds") or 300)
            pattern = shell_quote(arguments["pattern"])
            path = shell_quote(search_path)
            command = (
                f"if command -v rg >/dev/null 2>&1; then "
                f"rg -n {pattern} {path}; "
                f"elif command -v grep >/dev/null 2>&1; then "
                f"grep -RInE {pattern} {path}; "
                f"else echo 'rg and grep unavailable'; exit 127; fi"
            )
            result = self.remote_exec(command, cwd=cwd, timeout=timeout)
            return tool_text(render_command_result(result))

        if name == "sandbox_install_security_tools":
            tools = arguments.get("tools") or ["gitleaks", "trivy", "osv-scanner"]
            script = build_install_script(tools)
            result = self.remote_exec(script, timeout=1800)
            return tool_text(render_command_result(result))

        if name == "sandbox_run_vulnerability_suite":
            timeout = int(arguments.get("timeout_seconds") or 1800)
            if arguments.get("install_missing"):
                install = self.handle_tool_call("sandbox_install_security_tools", {"tools": ["gitleaks", "trivy", "osv-scanner"]})
                if install.get("isError"):
                    return install
            suite = build_scan_suite()
            result = self.remote_exec(suite, timeout=timeout)
            return tool_text(render_command_result(result))

        return tool_error(f"Unknown tool: {name}")


def build_install_script(tools):
    gitleaks_tag = github_latest_tag("gitleaks", "gitleaks")
    trivy_tag = github_latest_tag("aquasecurity", "trivy")
    osv_tag = github_latest_tag("google", "osv-scanner")
    selected = set(tools)
    commands = [
        "set -euo pipefail",
        "mkdir -p \"$HOME/.local/bin\"",
        "export PATH=\"$HOME/.local/bin:$PATH\"",
        "arch=$(uname -m)",
        "case \"$arch\" in x86_64|amd64) gitleaks_arch=x64; trivy_arch=64bit; osv_arch=amd64 ;; aarch64|arm64) gitleaks_arch=arm64; trivy_arch=ARM64; osv_arch=arm64 ;; *) echo \"Unsupported architecture: $arch\"; exit 1 ;; esac",
    ]
    if "gitleaks" in selected:
        commands += [
            f"tmp_gitleaks=$(mktemp -d)",
            f"curl -fsSL https://github.com/gitleaks/gitleaks/releases/download/{gitleaks_tag}/gitleaks_{gitleaks_tag.lstrip('v')}_linux_${{gitleaks_arch}}.tar.gz -o \"$tmp_gitleaks/gitleaks.tar.gz\"",
            "tar -xzf \"$tmp_gitleaks/gitleaks.tar.gz\" -C \"$tmp_gitleaks\"",
            "install -m 0755 \"$tmp_gitleaks/gitleaks\" \"$HOME/.local/bin/gitleaks\"",
        ]
    if "trivy" in selected:
        commands += [
            f"curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b \"$HOME/.local/bin\" {trivy_tag}",
        ]
    if "osv-scanner" in selected:
        commands += [
            f"curl -fsSL https://github.com/google/osv-scanner/releases/download/{osv_tag}/osv-scanner_linux_${{osv_arch}} -o \"$HOME/.local/bin/osv-scanner\"",
            "chmod +x \"$HOME/.local/bin/osv-scanner\"",
        ]
    commands += ["echo 'Installed requested security tools into $HOME/.local/bin'"]
    return "\n".join(commands)


def build_scan_suite():
    return """
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
echo "== sandbox status =="
pwd
echo
echo "== gitleaks =="
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks detect --no-banner --source . --redact || true
else
  echo "gitleaks unavailable"
fi
echo
echo "== trivy fs =="
if command -v trivy >/dev/null 2>&1; then
  trivy fs --quiet --severity MEDIUM,HIGH,CRITICAL . || true
else
  echo "trivy unavailable"
fi
echo
echo "== osv-scanner =="
if command -v osv-scanner >/dev/null 2>&1; then
  osv-scanner -r . || true
else
  echo "osv-scanner unavailable"
fi
echo
echo "== heuristics (rg) =="
if command -v rg >/dev/null 2>&1; then
  rg -n -e 'curl\\s+.*\\|\\s*sh' -e 'wget\\s+.*\\|\\s*sh' -e 'postinstall' -e 'eval\\(' -e 'process\\.env\\.[A-Z0-9_]{8,}' -g '!node_modules' -g '!dist' -g '!build' . || true
elif command -v grep >/dev/null 2>&1; then
  grep -RInE 'curl[[:space:]].*\\|[[:space:]]*sh|wget[[:space:]].*\\|[[:space:]]*sh|postinstall|eval\\(|process\\.env\\.[A-Z0-9_]{8,}' . || true
else
  echo "rg and grep unavailable"
fi
"""


def github_latest_tag(owner, repo):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{owner}/{repo}/releases/latest",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "plugin-sandbox-mcp"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload["tag_name"]


def render_command_result(result):
    return json.dumps(result, indent=2)


def shell_quote(value):
    return "'" + value.replace("'", "'\"'\"'") + "'"


def tool_text(text):
    return {"content": [{"type": "text", "text": text}]}


def tool_error(text):
    return {"content": [{"type": "text", "text": text}], "isError": True}


def make_error(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-file", required=True)
    args = parser.parse_args()
    bridge = SandboxBridge(args.state_file)

    while True:
        message = read_message()
        if message is None:
            break

        method = message.get("method")
        request_id = message.get("id")

        try:
            if method == "initialize":
                protocol_version = message.get("params", {}).get("protocolVersion", "2025-06-18")
                response = {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "protocolVersion": protocol_version,
                        "capabilities": {"tools": {"listChanged": False}},
                        "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                        "instructions": "Use these tools to inspect and operate the active Plugin Sandbox codespace.",
                    },
                }
                write_message(response)
                continue

            if method == "notifications/initialized":
                continue

            if method == "ping":
                write_message({"jsonrpc": "2.0", "id": request_id, "result": {}})
                continue

            if method == "tools/list":
                write_message({"jsonrpc": "2.0", "id": request_id, "result": {"tools": bridge.tools()}})
                continue

            if method == "tools/call":
                params = message.get("params", {})
                result = bridge.handle_tool_call(params.get("name"), params.get("arguments", {}))
                write_message({"jsonrpc": "2.0", "id": request_id, "result": result})
                continue

            if request_id is not None:
                write_message(make_error(request_id, -32601, f"Method not found: {method}"))
        except Exception as error:
            if request_id is not None:
                write_message(make_error(request_id, -32000, str(error)))


if __name__ == "__main__":
    main()
