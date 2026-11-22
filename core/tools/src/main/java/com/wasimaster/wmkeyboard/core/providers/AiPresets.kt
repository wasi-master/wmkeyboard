package com.wasimaster.wmkeyboard.core.providers

/**
 * A service the add sheet offers by name that is set up as an
 * [ProviderTypes.OPENAI_COMPATIBLE] row: its name and address filled in, so
 * only the key (and a model, which the list button fetches) is left to type.
 * Nothing about a preset is stored: the row it makes is a plain
 * OpenAI-compatible credential, which the user can change like any other.
 *
 * [label] is a brand name, so never translated. [url] is the address up to
 * and including the version segment, the way the compatible row takes it.
 * A [selfHosted] one is a server the user runs, whose address points at the
 * phone itself (Termux) until the user puts in their computer's.
 */
data class AiPreset(
    val id: String,
    val label: String,
    val url: String,
    /** A model filled in for a service that lists none, else blank: the list button fetches them. */
    val model: String = "",
    val selfHosted: Boolean = false,
)

object AiPresets {

    /*
     * Each address answered a keyless POST to /chat/completions with 400,
     * 401 or 402, never 404, on 2026-11-22. International hosts where a
     * service has a separate one for mainland China.
     */
    val all: List<AiPreset> = listOf(
        // One key for hundreds of models; `openrouter/auto` lets it pick.
        AiPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", model = "openrouter/auto"),
        AiPreset("groq", "Groq", "https://api.groq.com/openai/v1"),
        AiPreset("cerebras", "Cerebras", "https://api.cerebras.ai/v1"),
        AiPreset("mistral", "Mistral AI", "https://api.mistral.ai/v1"),
        AiPreset("together", "Together AI", "https://api.together.xyz/v1"),
        AiPreset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1"),
        AiPreset("venice", "Venice", "https://api.venice.ai/api/v1"),
        // No /models route, so the list button has nothing to fetch.
        AiPreset("perplexity", "Perplexity", "https://api.perplexity.ai", model = "sonar"),
        AiPreset("dashscope", "Qwen (Alibaba Cloud)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        AiPreset("moonshot", "Kimi (Moonshot AI)", "https://api.moonshot.ai/v1"),
        AiPreset("zai", "GLM (Z.ai)", "https://api.z.ai/api/paas/v4"),
        AiPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.com/v1"),
        // Servers of the user's own, at each one's default port.
        AiPreset("llama_cpp", "llama.cpp server", "http://127.0.0.1:8080/v1", selfHosted = true),
        AiPreset("koboldcpp", "KoboldCpp", "http://127.0.0.1:5001/v1", selfHosted = true),
        AiPreset("localai", "LocalAI", "http://127.0.0.1:8080/v1", selfHosted = true),
        AiPreset("vllm", "vLLM", "http://127.0.0.1:8000/v1", selfHosted = true),
        AiPreset("jan", "Jan", "http://127.0.0.1:1337/v1", selfHosted = true),
    )

    /** A new OpenAI-compatible credential set up from [preset], named after it. */
    fun credential(preset: AiPreset): ProviderCredential = ProviderCredential(
        id = ProviderCredential.newId(ProviderTypes.OPENAI_COMPATIBLE.id),
        typeId = ProviderTypes.OPENAI_COMPATIBLE.id,
        name = preset.label,
        url = preset.url,
        model = preset.model,
    )
}
