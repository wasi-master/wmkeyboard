package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.voice.executorch.HuggingFaceModels.Found
import com.wasimaster.wmkeyboard.core.voice.executorch.HuggingFaceModels.Ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceModelsTest {

    @Test
    fun `a repository is read from its name, its address or a page in it`() {
        val repo = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
        assertEquals(Ref(repo), HuggingFaceModels.parse(repo))
        assertEquals(Ref(repo), HuggingFaceModels.parse(" https://huggingface.co/$repo/ "))
        assertEquals(Ref(repo), HuggingFaceModels.parse("huggingface.co/$repo?not=this"))
        assertEquals(Ref(repo, "v2", "onnx/int8"), HuggingFaceModels.parse("https://huggingface.co/$repo/tree/v2/onnx/int8"))
        assertEquals(Ref(repo, "main", "tokenizer"), HuggingFaceModels.parse("https://huggingface.co/$repo/blob/main/tokenizer/vocab.json"))
        assertEquals(Ref(repo, "main", ""), HuggingFaceModels.parse("https://hf.co/$repo/resolve/main/encoder.int8.onnx"))
    }

    @Test
    fun `datasets, spaces and loose words are not repositories`() {
        assertNull(HuggingFaceModels.parse("https://huggingface.co/datasets/google/fleurs"))
        assertNull(HuggingFaceModels.parse("spaces/owner/demo"))
        assertNull(HuggingFaceModels.parse("qwen3"))
        assertNull(HuggingFaceModels.parse("owner/name/settings"))
        assertNull(HuggingFaceModels.parse("own er/name"))
    }

    @Test
    fun `the listing gives each file its size and its LFS hash where it has one`() {
        val listing = HuggingFaceModels.parseListing("o/r", LISTING)
        assertEquals("68818b2313fe77bd06f6a7c5068ff3ef59d02b8a", listing.commit)
        val vocab = listing.files.single { it.path == "tokenizer/vocab.json" }
        assertEquals(2_776_833L, vocab.bytes)
        assertEquals("ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910", vocab.sha256)
        assertNull(listing.files.single { it.path == "tokenizer/merges.txt" }.sha256)
    }

    @Test
    fun `a Qwen3-ASR repository gives its graphs and tokenizer, not its test clips`() {
        val found = HuggingFaceModels.find(HuggingFaceModels.parseListing("o/r", LISTING)).single() as Found.Qwen3
        assertEquals(
            listOf(
                "conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
                "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json",
            ),
            found.files.map { it.path },
        )
        assertEquals(44_148_281L + 182_491_662L + 755_914_231L + 2_776_833L + 1_671_853L + 12_487L, found.bytes)
    }

    @Test
    fun `a streaming transducer repository is a Sherpa model`() {
        val listing = HuggingFaceModels.Listing(
            "o/r", "abc",
            listOf("tokens.txt", "encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "test_wavs/0.wav")
                .map { HuggingFaceModels.RepoFile(it, 1_000L, null) },
        )
        val found = HuggingFaceModels.find(listing).single() as Found.Sherpa
        assertEquals(4, found.files.size)
        assertTrue(found.files.none { it.path == "encoder-epoch-99-avg-1.onnx" })
    }

    @Test
    fun `whisper cpp files are offered one by one, smallest first, and only under the folder asked for`() {
        val big = 100L shl 20
        val listing = HuggingFaceModels.Listing(
            "o/r", "abc",
            listOf(
                HuggingFaceModels.RepoFile("ggml-base.bin", big * 2, "a".repeat(64)),
                HuggingFaceModels.RepoFile("ggml-tiny.bin", big, "b".repeat(64)),
                HuggingFaceModels.RepoFile("for-tests-ggml-tiny.bin", 1_000L, null),
                HuggingFaceModels.RepoFile("other/ggml-small.bin", big * 3, "c".repeat(64)),
                HuggingFaceModels.RepoFile("README.md", 10L, null),
            ),
        )
        val all = HuggingFaceModels.find(listing).single() as Found.Whisper
        assertEquals(listOf("ggml-tiny.bin", "ggml-base.bin", "other/ggml-small.bin"), all.files.map { it.path })
        val folder = HuggingFaceModels.find(listing, "other").single() as Found.Whisper
        assertEquals(listOf("other/ggml-small.bin"), folder.files.map { it.path })
    }

    @Test
    fun `files download from the commit the listing named`() {
        assertEquals(
            "https://huggingface.co/o/r/resolve/abc/tokenizer/my%20vocab.json",
            HuggingFaceModels.fileUrl("o/r", "abc", "tokenizer/my vocab.json"),
        )
        assertEquals(
            "https://huggingface.co/api/models/o/r/revision/refs%2Fpr%2F1?blobs=true",
            HuggingFaceModels.listingUrl(Ref("o/r", "refs/pr/1")),
        )
    }

    private companion object {
        // Trimmed from the real answer for csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.
        const val LISTING = """
        {"id":"o/r","sha":"68818b2313fe77bd06f6a7c5068ff3ef59d02b8a","siblings":[
          {"rfilename":".gitattributes","size":1739},
          {"rfilename":"README.md","size":328},
          {"rfilename":"conv_frontend.onnx","size":44148281,"lfs":{"sha256":"d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e","size":44148281}},
          {"rfilename":"decoder.int8.onnx","size":755914231,"lfs":{"sha256":"4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771","size":755914231}},
          {"rfilename":"encoder.int8.onnx","size":182491662,"lfs":{"sha256":"60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9","size":182491662}},
          {"rfilename":"test_wavs/ar1.wav","size":168044,"lfs":{"sha256":"700b3c274f2fedffbb6016f03c574adaad7aa0291acc1a1ba72f07112051073f","size":168044}},
          {"rfilename":"test_wavs/transcript.txt","size":5386},
          {"rfilename":"tokenizer/merges.txt","size":1671853},
          {"rfilename":"tokenizer/tokenizer_config.json","size":12487,"lfs":{"sha256":"4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c","size":12487}},
          {"rfilename":"tokenizer/vocab.json","size":2776833,"lfs":{"sha256":"ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910","size":2776833}}
        ]}
        """
    }
}
