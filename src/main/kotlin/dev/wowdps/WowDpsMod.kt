package dev.wowdps

import dev.wowdps.server.DamageHookContext
import dev.wowdps.team.PartyTeamIntegration
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object WowDpsMod : ModInitializer {
    const val MOD_ID = "wowdps"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        DamageHookContext.registerServerHooks()
        LOGGER.info(
            "[WoW Details! DPS Meter v7.0] Initialized (FTB Teams loaded={}, Open Parties & Claims loaded={}).",
            PartyTeamIntegration.isFtbTeamsLoaded,
            PartyTeamIntegration.isOpacLoaded
        )
    }
}
