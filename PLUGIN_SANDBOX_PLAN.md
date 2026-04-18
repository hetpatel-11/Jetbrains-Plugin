# JetBrains Plugin Sandbox Plan

## What We Are Building

We want a JetBrains plugin that helps a user explore software ideas safely before changing the current codebase.

The plugin should:

1. Take a PRD or idea from the user.
2. Show the top 3 recommendations for what to try.
3. Let the user click `Try it out`.
4. Open a sandbox view for that recommendation.
5. Run security and vulnerability checks in parallel while the user explores the sandbox.
6. Let the user click `Implement` if they like what they see.
7. Generate a strong implementation prompt that the coding agent can use to apply the idea to the current repo.
8. Allow `Discard` if the recommendation is not good.

This should work even without deep integration into JetBrains AI Chat.

AI Chat is helpful, but for now the plugin is the main product.

## Simple Product Story

The user says:

`I have a product idea or PRD. Show me the best 3 ways to implement it.`

The plugin responds by showing:

- Recommendation 1
- Recommendation 2
- Recommendation 3

The user clicks one recommendation and chooses:

- `Try it out`
- `Implement`
- `Discard`

If the user clicks `Try it out`, the plugin opens a sandbox and starts safety scans in the background.

If the user clicks `Implement`, the plugin prepares a detailed prompt for the coding agent to modify the current repo.

## Main Principle

Do not try to make AI Chat own the whole workflow.

The plugin should own:

- recommendation UI
- sandbox UI
- state tracking
- security scan orchestration
- implementation prompt generation

AI Chat or Codex should help with:

- generating recommendations
- revising recommendations
- understanding the implementation prompt
- writing code in the current repo

## Why This Approach

This is the cleanest design because:

1. JetBrains plugins are good at native IDE UI.
2. AI Chat is good at reasoning and coding.
3. Embedding custom app-like UI directly inside AI Chat is uncertain.
4. A plugin can still work well even if AI Chat integration is limited.

## MVP Scope

Build the smallest useful version first.

### MVP Features

1. A JetBrains tool window or side panel.
2. A text area where the user pastes a PRD or idea.
3. A button to generate 3 recommendations.
4. Recommendation cards with:
   - title
   - summary
   - tradeoffs
   - risk level
   - buttons for `Try it out`, `Implement`, `Discard`
5. A sandbox panel.
6. A scan status panel.
7. An implementation prompt panel.
8. A `Copy Prompt` button.

### What Can Be Fake in MVP

These can be mocked at first:

- recommendation generation
- Codespaces session launch
- remote command execution in sandbox
- scan output

The important thing is to get the workflow and architecture right first.

## Final User Flow

### Step 1: User enters PRD

The user pastes a product requirement, issue, or feature description.

### Step 2: Plugin generates recommendations

The plugin shows top 3 recommendations.

Each recommendation should include:

- what it does
- why it is useful
- complexity
- risk
- when to choose it

### Step 3: User clicks `Try it out`

The plugin opens the sandbox view.

At the same time, the plugin starts a security scan in parallel.

The user does not need to wait for the scan to finish before seeing the sandbox.

### Step 4: Plugin runs safety checks

The scan runs in the background and updates the UI:

- `Scanning`
- `Warnings found`
- `High risk found`
- `Scan complete`

### Step 5: User evaluates the sandbox

If the recommendation looks good, the user clicks `Implement`.

If not, the user clicks `Discard`.

### Step 6: Plugin generates implementation prompt

The prompt should contain:

- chosen recommendation
- reason for choice
- what was tested in sandbox
- scan summary
- affected areas of the codebase
- acceptance criteria
- constraints

The user can then use that prompt with the coding agent.

## Security Model

The sandbox is isolated, so the user can start exploring immediately.

However, we still want scanning in parallel to reduce risk and inform the user.

This means:

- sandbox access is fast
- scanning does not block the basic flow
- serious issues are still surfaced clearly

### Security Status Levels

Use three levels:

1. `Info`
   - no obvious issue
   - low risk items

2. `Warning`
   - moderate CVEs
   - suspicious scripts
   - risky config

3. `High Risk`
   - critical vulnerabilities
   - detected secrets
   - obviously dangerous scripts or setup

### What the Plugin Should Show

For each scan, show:

- tool name
- status
- key findings
- recommendation

Example:

- `gitleaks`: no secrets found
- `trivy`: 2 medium vulnerabilities
- `osv-scanner`: 1 critical package vulnerability
- `heuristics`: suspicious postinstall script found

## What Tools to Use for Vulnerability Checks

Do not rely only on `grep`.

Use layered scanning.

### 1. Dependency Scanning

Use the tools that match the detected tech stack:

- `npm audit`
- `pnpm audit`
- `yarn npm audit`
- `pip-audit`
- `cargo audit`
- `osv-scanner`
- `trivy fs .`

### 2. Secrets Scanning

Use:

- `gitleaks`

Optional later:

- `trufflehog`

### 3. Heuristic Pattern Checks

Use `rg` or similar to look for dangerous patterns such as:

- `curl | sh`
- `wget | sh`
- suspicious `postinstall`
- shell execution from app code
- hardcoded tokens
- broad network binds
- risky Dockerfile instructions

### 4. Config and Container Checks

Use:

- `trivy config`
- optionally `hadolint`

## Important Design Decision

The plugin should control the scan lifecycle.

The coding agent should not be responsible for enforcement.

The plugin should:

- start scans
- collect results
- normalize findings
- show status to the user
- decide whether to warn or block based on configured rules

## What “Bridge” Means

Later, the plugin will need a bridge to a real sandbox such as GitHub Codespaces.

That bridge should support:

- creating or opening a sandbox session
- cloning or opening the target repo
- running commands in the sandbox
- returning command output
- reading files or reports
- resetting or stopping the sandbox

For MVP, this can be a mock implementation.

For production, this can be backed by a real Codespaces integration.

## Suggested Architecture

Use these parts:

### 1. Plugin UI Layer

Responsible for:

- PRD input
- recommendation cards
- sandbox panel
- scan status panel
- implementation prompt panel

### 2. Plugin State Layer

Responsible for:

- active PRD
- recommendation list
- selected recommendation
- sandbox session metadata
- scan results
- generated prompt

### 3. Recommendation Engine

Responsible for:

- generating top 3 options from the PRD
- ranking by cost, complexity, and risk

This can be heuristic or mocked first.

### 4. Sandbox Bridge

Responsible for:

- sandbox launch
- command execution
- scan execution
- command logs

### 5. Scan Orchestrator

Responsible for:

- detecting project stack
- choosing scanners
- running them in parallel
- normalizing results into one format

### 6. Prompt Generator

Responsible for:

- creating the final implementation handoff prompt for the coding agent

## What to Build First

Do this in order.

### Phase 1: Plugin Skeleton

Build:

- Gradle-based IntelliJ plugin project
- plugin metadata
- tool window
- Kotlin source structure

### Phase 2: Workflow UI

Build:

- PRD text area
- generate recommendations button
- top 3 recommendation cards
- try/implement/discard actions

### Phase 3: Sandbox and Scanning UI

Build:

- sandbox panel
- fake launch state
- scan progress list
- scan result summary

### Phase 4: Prompt Generation

Build:

- implementation brief builder
- prompt preview
- copy button

### Phase 5: Real Integrations

Add:

- real recommendation generation
- real Codespaces bridge
- real vulnerability scanners

## Prompt Template for `Implement`

Use something like this:

```text
Implement the selected recommendation in the current repository.

Selected recommendation:
[title]

Why it was chosen:
[reason]

What was validated in sandbox:
[summary]

Security scan summary:
[scan results]

Expected outcome:
[target behavior]

Acceptance criteria:
- [criterion 1]
- [criterion 2]
- [criterion 3]

Constraints:
- Keep changes scoped
- Do not break existing behavior
- Reuse existing patterns where possible

Likely affected areas:
- [module/file area 1]
- [module/file area 2]

Please implement the change, explain the edits, and note any follow-up work.
```

## What Not to Do Right Now

Avoid these in the first version:

- deep AI Chat embedding
- custom UI inside AI Chat bubbles
- perfect Codespaces automation
- perfect security policy engine
- too much MCP work

These can come later.

Right now the goal is a working plugin workflow.

## Definition of Done for MVP

The MVP is done when:

1. The plugin opens in JetBrains.
2. The user can paste a PRD.
3. The plugin shows 3 recommendations.
4. The user can click `Try it out`.
5. A sandbox view opens.
6. A scan starts in parallel and shows results.
7. The user can click `Implement`.
8. The plugin generates a strong prompt for the coding agent.

## Short Summary for a Beginner

If you know nothing, think of the product like this:

The plugin is a smart control panel inside JetBrains.

It helps the user:

- choose one of several implementation ideas
- try that idea safely in a sandbox
- see security findings while trying it
- turn the chosen idea into a clean prompt for the coding agent

Start with the plugin workflow first.

Do not start with AI Chat integration.

Do not start with full Codespaces automation.

Do not start with MCP UI.

Build the flow, state, and scan model first.
