package com.dosely.app.ai

/**
 * Catalog of on-device models offered in onboarding. URLs are stable HuggingFace
 * resolve endpoints over the `main` branch of litert-community repos.
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val description: String,
    /** Approximate peak RAM needed, in GB. */
    val minDeviceRamGb: Int,
)

object GemmaModelCatalog {

    val gemma4E2B = ModelOption(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        description = "Runs on CPU and GPU. Best compatibility for phones.",
        minDeviceRamGb = 8,
    )

    val default: ModelOption get() = gemma4E2B

    val all = listOf(gemma4E2B)

    fun url(option: ModelOption): String =
        "https://huggingface.co/${option.repo}/resolve/main/${option.fileName}"

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.0f MB", bytes / 1_000_000.0)
        else -> "$bytes B"
    }
}
