package social.saigon.ai

import kotlin.random.Random

/**
 * Per-room identity (DESIGN §5): a human-sounding name + an abstract sketch-style avatar.
 */
data class GeneratedIdentity(val name: String, val avatarUrl: String)

/**
 * Identity generation via Qwen AI (DESIGN §5, §12).
 *
 * This is the integration seam. The default [LocalIdentityGenerator] produces deterministic-ish
 * local identities so the platform runs without network/API keys; [QwenIdentityGenerator] is the
 * scaffolding for the real Qwen AI API call.
 */
interface IdentityGenerator {
    suspend fun generate(seed: String): GeneratedIdentity
}

/**
 * Local, no-network identity generator used by default and in tests.
 */
class LocalIdentityGenerator(
    private val random: Random = Random.Default,
) : IdentityGenerator {

    private val names = listOf(
        "Minh", "Linh", "Alex", "Nam", "Mai", "Khoa", "An", "Vy", "Sam", "Quan",
        "Tâm", "Phúc", "Hà", "Long", "Trang", "Duy", "Thảo", "Bình",
    )

    override suspend fun generate(seed: String): GeneratedIdentity {
        val name = names[random.nextInt(names.size)]
        // Abstract sketch-style avatar placeholder, deterministic per generated token.
        val token = (seed.hashCode() xor random.nextInt()).toString(16)
        return GeneratedIdentity(
            name = name,
            avatarUrl = "https://avatars.saigon.social/sketch/$token.svg",
        )
    }
}

/**
 * Scaffolding for the real Qwen AI API integration. Wired with config but intentionally delegates
 * to [LocalIdentityGenerator] until an API key + endpoint are provisioned, so nothing breaks in
 * environments without external access.
 */
class QwenIdentityGenerator(
    private val apiKey: String?,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val fallback: IdentityGenerator = LocalIdentityGenerator(),
) : IdentityGenerator {

    override suspend fun generate(seed: String): GeneratedIdentity {
        if (apiKey.isNullOrBlank()) {
            return fallback.generate(seed)
        }
        // TODO(Phase 2): call Qwen AI at $endpoint to generate name + abstract avatar.
        return fallback.generate(seed)
    }

    companion object {
        const val DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
    }
}
