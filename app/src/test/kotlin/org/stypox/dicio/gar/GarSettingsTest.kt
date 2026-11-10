package org.stypox.dicio.gar

import androidx.datastore.core.CorruptionException
import cc.aaro.gar.router.format.ModelFamily
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.flow.first
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.model.ModelCatalog
import org.stypox.dicio.gar.model.selectedModelFamily
import org.stypox.dicio.gar.settings.GarModel
import org.stypox.dicio.gar.settings.GarModelFamily
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private fun settings(build: GarSettings.Builder.() -> Unit): GarSettings =
    GarSettingsSerializer.defaultValue.toBuilder().apply(build).build()

class GarSettingsTest : StringSpec({
    "a new install has the model and the confirmations on" {
        val defaults = GarSettingsSerializer.defaultValue
        defaults.llmEnabled shouldBe true
        defaults.model shouldBe GarModel.GAR_MODEL_QWEN3_0_6B
        defaults.confirmLlmActions shouldBe true
        defaults.functionGemmaTermsAccepted shouldBe false
        defaults.customModelFamily shouldBe GarModelFamily.GAR_MODEL_FAMILY_HERMES
        defaults.customModelName shouldBe ""
    }

    "continued listening is on and typed answers are shown only, for new and old installs" {
        for (settings in listOf(
            GarSettingsSerializer.defaultValue,
            GarSettings.getDefaultInstance(), // a file written before the settings existed
        )) {
            settings.continuedListeningOff shouldBe false
            GarContinuedListening.settingOn(settings) shouldBe true
            settings.speakTypedAnswers shouldBe false
        }
        val changed = settings {
            setContinuedListeningOff(true)
            setSpeakTypedAnswers(true)
        }
        GarContinuedListening.settingOn(changed) shouldBe false
        val bytes = ByteArrayOutputStream().also { GarSettingsSerializer.writeTo(changed, it) }
        val read = GarSettingsSerializer.readFrom(ByteArrayInputStream(bytes.toByteArray()))
        read.continuedListeningOff shouldBe true
        read.speakTypedAnswers shouldBe true
        // GarSettings fields 50 and 51
        GarSettings.CONTINUED_LISTENING_OFF_FIELD_NUMBER shouldBe 50
        GarSettings.SPEAK_TYPED_ANSWERS_FIELD_NUMBER shouldBe 51
    }

    "settings are written and read back" {
        val changed = settings {
            setLlmEnabled(false)
            setModel(GarModel.GAR_MODEL_CUSTOM)
            setCustomModelFamily(GarModelFamily.GAR_MODEL_FAMILY_FUNCTION_GEMMA)
            setCustomModelName("x.gguf")
            setFunctionGemmaTermsAccepted(true)
        }
        val bytes = ByteArrayOutputStream().also { GarSettingsSerializer.writeTo(changed, it) }
        GarSettingsSerializer.readFrom(ByteArrayInputStream(bytes.toByteArray())) shouldBe changed
    }

    "a damaged file is reported as corrupt, so that the store starts over from the defaults" {
        // a length-delimited field cut off after one of its five bytes
        shouldThrow<CorruptionException> {
            GarSettingsSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x0A, 0x05, 0x01)))
        }
    }

    "an empty file is all proto defaults, not the defaults of a new install" {
        // DataStore writes nothing only when every field has its proto default, so this is what
        // the user chose then (model off, no confirmations)
        val empty = GarSettingsSerializer.readFrom(ByteArrayInputStream(byteArrayOf()))
        empty shouldBe GarSettings.getDefaultInstance()
        empty.llmEnabled shouldBe false
        empty.confirmLlmActions shouldBe false
    }

    "the preview store keeps updates" {
        val store = GarSettingsModule.newDataStoreForPreviews()
        store.updateData { it.toBuilder().setLlmEnabled(false).build() }
        store.data.first().llmEnabled shouldBe false
        store.data.first().confirmLlmActions shouldBe true
    }

    "the downloadable models are pinned and consistent" {
        ModelCatalog.forSetting(GarModel.GAR_MODEL_CUSTOM) shouldBe null
        ModelCatalog.forSetting(GarModel.GAR_MODEL_QWEN3_0_6B) shouldBe ModelCatalog.QWEN3_0_6B
        ModelCatalog.forSetting(GarModel.GAR_MODEL_FUNCTION_GEMMA_270M) shouldBe
            ModelCatalog.FUNCTION_GEMMA_270M

        // a typo here only shows as a checksum mismatch after a download of 300 MB or more
        for (model in ModelCatalog.all) {
            withClue(model.displayName) {
                model.url shouldEndWith "/" + model.fileName
                model.url shouldMatch Regex("https://huggingface\\.co/.+/resolve/[0-9a-f]{40}/.+")
                model.sha256 shouldMatch Regex("[0-9a-f]{64}")
                (model.sizeBytes > 0) shouldBe true
                model.fileName.contains('/') shouldBe false
                // the download page asks to accept the Gemma terms (functionGemmaTermsAccepted)
                (model.termsUrl != null) shouldBe (model.family == ModelFamily.FUNCTION_GEMMA)
                if (model.termsUrl == null) {
                    model.usePolicyUrl shouldBe null
                }
            }
        }
        ModelCatalog.all.map { it.setting }.distinct().size shouldBe ModelCatalog.all.size
        ModelCatalog.all.map { it.fileName }.distinct().size shouldBe ModelCatalog.all.size
        ModelCatalog.all.map { it.fileName } shouldNotContain GarModelManager.CUSTOM_MODEL_FILE
    }

    "the prompt family of the selected model" {
        settings { setModel(GarModel.GAR_MODEL_QWEN3_0_6B) }.selectedModelFamily() shouldBe
            ModelFamily.HERMES
        settings {
            setModel(GarModel.GAR_MODEL_CUSTOM)
            setCustomModelFamily(GarModelFamily.GAR_MODEL_FAMILY_HERMES)
        }.selectedModelFamily() shouldBe ModelFamily.HERMES
        settings { setModel(GarModel.GAR_MODEL_FUNCTION_GEMMA_270M) }.selectedModelFamily() shouldBe
            ModelFamily.FUNCTION_GEMMA
        settings {
            setModel(GarModel.GAR_MODEL_CUSTOM)
            setCustomModelFamily(GarModelFamily.GAR_MODEL_FAMILY_FUNCTION_GEMMA)
        }.selectedModelFamily() shouldBe ModelFamily.FUNCTION_GEMMA
        // values a newer version wrote: the default model's family
        settings { setModelValue(99) }.selectedModelFamily() shouldBe ModelFamily.HERMES
        settings {
            setModel(GarModel.GAR_MODEL_CUSTOM)
            setCustomModelFamilyValue(99)
        }.selectedModelFamily() shouldBe ModelFamily.HERMES
    }
})
