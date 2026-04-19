package com.hetpatel.pluginsandbox.services

import com.hetpatel.pluginsandbox.model.Recommendation
import com.hetpatel.pluginsandbox.model.RecommendationKind
import com.hetpatel.pluginsandbox.model.RiskLevel
import java.util.Locale
import java.util.UUID

class RecommendationEngine {
    fun generate(prd: String): List<Recommendation> {
        val focus = summarize(prd.trim().ifBlank { "the requested idea" })

        return listOf(
            Recommendation(
                id = UUID.randomUUID().toString(),
                kind = RecommendationKind.FAST_API_SIDECAR,
                title = "FastAPI Reference Sandbox for $focus",
                summary = "Use the real FastAPI repository as the sandbox target so you can explore a production-grade Python API codebase and run a real demo directly inside that Codespace.",
                tradeoffs = "Best when the feature is API-first, but it validates against the FastAPI project structure rather than your current repository shape.",
                riskLevel = RiskLevel.MEDIUM,
                complexity = "Medium",
                whenToChoose = "Choose this when the idea is strongly API, backend, async, validation, or service oriented.",
                validationPlan = "Launch a Codespace for FastAPI, run the demo app and security checks inside that sandbox, and inspect the live source tree before choosing an implementation path.",
                affectedAreas = listOf("FastAPI routing patterns", "Pydantic model usage", "Project layout and test conventions"),
                repositoryUrl = "https://github.com/fastapi/fastapi.git",
                repositorySlug = "fastapi/fastapi",
            ),
            Recommendation(
                id = UUID.randomUUID().toString(),
                kind = RecommendationKind.DIRECT_IN_REPO,
                title = "Flask Reference Sandbox for $focus",
                summary = "Use the real Flask repository as a lightweight reference sandbox when you want a smaller surface area and a minimal web framework comparison point.",
                tradeoffs = "Simpler than FastAPI, but it gives you fewer batteries-included patterns for typed request/response workflows.",
                riskLevel = RiskLevel.LOW,
                complexity = "Low to medium",
                whenToChoose = "Choose this when the feature benefits from a small HTTP surface and you want to compare against a minimalist Python web stack.",
                validationPlan = "Launch a Codespace for Flask, run the tutorial app and security checks inside that sandbox, and compare the request lifecycle against the FastAPI option.",
                affectedAreas = listOf("Routing and request lifecycle", "Extension boundaries", "Minimal service scaffolding"),
                repositoryUrl = "https://github.com/pallets/flask.git",
                repositorySlug = "pallets/flask",
            ),
            Recommendation(
                id = UUID.randomUUID().toString(),
                kind = RecommendationKind.ADAPTER_SANDBOX,
                title = "Django Reference Sandbox for $focus",
                summary = "Use the real Django repository as a heavier full-stack reference sandbox when the idea may need admin, ORM, auth, or batteries-included patterns.",
                tradeoffs = "Provides more built-in systems to evaluate, but the framework is heavier and may be more than the feature actually needs.",
                riskLevel = RiskLevel.HIGH,
                complexity = "Medium to high",
                whenToChoose = "Choose this when the idea touches data modeling, auth, admin, or larger full-stack workflow concerns.",
                validationPlan = "Launch a Codespace for Django, run a generated demo project and security checks inside that sandbox, and inspect whether the added framework weight is justified.",
                affectedAreas = listOf("ORM and data model patterns", "Auth and admin capabilities", "Full-stack project organization"),
                repositoryUrl = "https://github.com/django/django.git",
                repositorySlug = "django/django",
            ),
        )
    }

    private fun summarize(prd: String): String {
        val words = prd.replace(Regex("\\s+"), " ").trim().split(" ")
        return words.take(6).joinToString(" ").replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }.ifBlank { "This Idea" }
    }
}
