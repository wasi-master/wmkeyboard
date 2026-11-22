package com.wasimaster.wmkeyboard.core.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which on-device models the reply tool may hand a screenshot to (#234). */
class LocalLlmCatalogVisionTest {

    @Test
    fun `the Gemma 4 rows and the GGUF vision rows say they can see`() {
        val seeing = LocalLlmCatalog.models.filter { it.vision }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "gemma4-e2b", "gemma4-e4b",
                "gguf-qwen3-vl-4b", "gguf-ministral3-3b", "gguf-qwen2.5-vl-3b", "gguf-qwen3-vl-2b",
                "gguf-minicpm-v4.7-1b", "gguf-smolvlm-500m",
            ),
            seeing,
        )
    }

    @Test
    fun `a catalog model answers for itself`() {
        assertEquals(true, LocalLlmCatalog.canSee("gemma4-e2b"))
        assertEquals(false, LocalLlmCatalog.canSee("qwen3-0.6b"))
    }

    @Test
    fun `an imported file is unknown, so it gets tried`() {
        assertNull(LocalLlmCatalog.canSee("custom:my-model.litertlm"))
        assertNull(LocalLlmCatalog.canSee("custom:my-model.gguf"))
    }

    @Test
    fun `no selection is unknown too`() {
        assertNull(LocalLlmCatalog.canSee(null))
        assertNull(LocalLlmCatalog.canSee(""))
    }
}
