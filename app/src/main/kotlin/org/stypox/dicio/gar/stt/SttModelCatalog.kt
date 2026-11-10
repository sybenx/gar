package org.stypox.dicio.gar.stt

import org.stypox.dicio.gar.settings.GarSttModel

/**
 * A file the Whisper input device needs. `DownloadableModel` is about the language model (prompt
 * family, terms), so speech files have their own type.
 *
 * @param setting the model choice in GAR's settings, or null for a file every model needs (VAD)
 */
data class SttDownload(
    val setting: GarSttModel?,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseName: String,
)

/**
 * The speech files GAR can download, pinned by size and sha256, so a file replaced on the server
 * fails as damaged instead of loading. For now the Whisper files come from FUTO's download server
 * (linked from the MIT futo-org/whisper-acft README; its paths carry no revision); before the
 * Obtainium release they move to GAR's own GitHub release assets.
 */
object SttModelCatalog {
    /** FUTO's ACFT fine-tune of Whisper base.en, ggml q8_0 (the default). */
    val BASE_EN = SttDownload(
        setting = GarSttModel.GAR_STT_MODEL_BASE_EN,
        displayName = "Whisper base.en",
        fileName = "ggml-base.en-acft-q8_0.bin",
        url = "https://dl.voiceinput.futo.org/VoiceInput/base_en_acft_q8_0.bin",
        sizeBytes = 81_781_811L,
        sha256 = "e9b4b7b81b8a28769e8aa9962aa39bb9f21b622cf6a63982e93f065ed5caf1c8",
        licenseName = "Apache-2.0",
    )

    /** FUTO's ACFT fine-tune of Whisper tiny.en, ggml q8_0. */
    val TINY_EN = SttDownload(
        setting = GarSttModel.GAR_STT_MODEL_TINY_EN,
        displayName = "Whisper tiny.en",
        fileName = "ggml-tiny.en-acft-q8_0.bin",
        url = "https://dl.voiceinput.futo.org/VoiceInput/tiny_en_acft_q8_0.bin",
        sizeBytes = 43_550_795L,
        sha256 = "4b5480aa1b14a7efc5b578ef176510970a898049671c3cd237285b3e3f6bfbfc",
        licenseName = "Apache-2.0",
    )

    /** Silero VAD v6.2.0 converted for whisper.cpp, downloaded with whichever model is chosen. */
    val SILERO_VAD = SttDownload(
        setting = null,
        displayName = "Silero VAD v6.2.0",
        fileName = "ggml-silero-v6.2.0.bin",
        url = "https://huggingface.co/ggml-org/whisper-vad/resolve/" +
                "9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin",
        sizeBytes = 885_098L,
        sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
        licenseName = "MIT",
    )

    val models = listOf(BASE_EN, TINY_EN)

    /** The model for [setting]; base.en for an unknown value (a newer version's setting). */
    fun forSetting(setting: GarSttModel): SttDownload =
        models.firstOrNull { it.setting == setting } ?: BASE_EN

    /** What a download of [setting] fetches at most: its model plus the VAD file. */
    fun downloadSize(setting: GarSttModel): Long = forSetting(setting).sizeBytes + SILERO_VAD.sizeBytes
}
