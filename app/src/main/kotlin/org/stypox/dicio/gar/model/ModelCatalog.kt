package org.stypox.dicio.gar.model

import cc.aaro.gar.router.format.ModelFamily
import org.stypox.dicio.gar.settings.GarModel
import org.stypox.dicio.gar.settings.GarModelFamily
import org.stypox.dicio.gar.settings.GarSettings

/** A model GAR can download. URLs point to pinned Hugging Face revisions. */
data class DownloadableModel(
    val setting: GarModel,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val family: ModelFamily,
    val licenseName: String,
    /** Terms the user must read and accept before downloading, or null if none. */
    val termsUrl: String?,
    /** A use policy that is part of [termsUrl], linked next to it. */
    val usePolicyUrl: String? = null,
)

object ModelCatalog {
    val QWEN3_0_6B = DownloadableModel(
        setting = GarModel.GAR_MODEL_QWEN3_0_6B,
        displayName = "Qwen3 0.6B",
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/" +
                "50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf",
        sizeBytes = 396705472L,
        sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        family = ModelFamily.HERMES,
        licenseName = "Apache-2.0",
        termsUrl = null,
    )

    val FUNCTION_GEMMA_270M = DownloadableModel(
        setting = GarModel.GAR_MODEL_FUNCTION_GEMMA_270M,
        displayName = "FunctionGemma 270M",
        fileName = "functiongemma-270m-it-q8_0.gguf",
        url = "https://huggingface.co/ggml-org/functiongemma-270m-it-GGUF/resolve/" +
                "2566ce14aedfc14fdd0de955ba67346425e67126/functiongemma-270m-it-q8_0.gguf",
        sizeBytes = 291557792L,
        sha256 = "83940d4dd9676710856f43523bed096164a595a96f6b34771610a03937de5270",
        family = ModelFamily.FUNCTION_GEMMA,
        licenseName = "Gemma Terms of Use",
        termsUrl = "https://ai.google.dev/gemma/terms",
        usePolicyUrl = "https://ai.google.dev/gemma/prohibited_use_policy",
    )

    val all = listOf(QWEN3_0_6B, FUNCTION_GEMMA_270M)

    /** @return the downloadable model for [setting], or null for [GarModel.GAR_MODEL_CUSTOM] */
    fun forSetting(setting: GarModel): DownloadableModel? = all.firstOrNull { it.setting == setting }
}

/** How to prompt the model selected in these settings. */
fun GarSettings.selectedModelFamily(): ModelFamily = if (model == GarModel.GAR_MODEL_CUSTOM) {
    when (customModelFamily) {
        GarModelFamily.GAR_MODEL_FAMILY_FUNCTION_GEMMA -> ModelFamily.FUNCTION_GEMMA
        else -> ModelFamily.HERMES
    }
} else {
    (ModelCatalog.forSetting(model) ?: ModelCatalog.QWEN3_0_6B).family
}
