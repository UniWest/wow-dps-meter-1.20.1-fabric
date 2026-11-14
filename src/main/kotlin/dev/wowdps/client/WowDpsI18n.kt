package dev.wowdps.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.resource.language.I18n

/**
 * Client-Side Localization Helper:
 * Dynamically resolves translations from loaded resource packs (assets/wowdps/lang/{ru_ru, en_us}.json)
 * based on the player's active language in Minecraft settings (`client.options.language`).
 * Also decodes `@tr:key|arg1|arg2` tokens sent from the server so Russian and English clients
 * on the same server each see their own language!
 */
object WowDpsI18n {

    fun tr(key: String, vararg args: Any): String {
        return try {
            if (I18n.hasTranslation(key)) {
                I18n.translate(key, *args)
            } else if (args.isNotEmpty()) {
                String.format(key, *args)
            } else {
                key
            }
        } catch (_: Exception) {
            key
        }
    }

    fun resolveServerText(raw: String): String {
        if (!raw.startsWith("@tr:")) return raw
        val payload = raw.removePrefix("@tr:")
        val parts = payload.split("|")
        val key = parts[0]
        return when (parts.size) {
            1 -> tr(key)
            2 -> tr(key, resolveServerText(parts[1]))
            3 -> tr(key, resolveServerText(parts[1]), resolveServerText(parts[2]))
            else -> tr(key)
        }
    }

    fun isRussian(): Boolean {
        val lang = MinecraftClient.getInstance().options?.language ?: return false
        return lang.startsWith("ru", ignoreCase = true)
    }
}
