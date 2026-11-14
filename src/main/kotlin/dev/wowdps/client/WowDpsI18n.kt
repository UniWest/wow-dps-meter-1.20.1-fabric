package dev.wowdps.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.resource.language.I18n

/**
 * Client-Side Localization Helper:
 * Dynamically resolves translations from loaded resource packs (including assets/wowdps/lang/{ru_ru, en_us}.json)
 * based on the player's active language in Minecraft settings (`client.options.language`)!
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

    fun isRussian(): Boolean {
        val lang = MinecraftClient.getInstance().options?.language ?: return true
        return lang.startsWith("ru", ignoreCase = true)
    }
}
