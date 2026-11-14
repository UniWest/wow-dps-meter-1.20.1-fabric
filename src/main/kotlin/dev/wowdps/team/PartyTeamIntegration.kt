package dev.wowdps.team

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import dev.wowdps.client.WowDpsI18n
import net.minecraft.text.Text
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

/**
 * Universal Party & Teammate Integration for Minecraft 1.20.1 Fabric (v7.0):
 * Supports:
 * 1) **FTB Teams** (`ftbteams` — `dev.ftb.mods.ftbteams.api.FTBTeamsAPI`)
 * 2) **Open Parties and Claims** (`openpartiesandclaims` — `xaero.pac.common.server.api.OpenPACServerAPI` & `xaero.pac.client.api.OpenPACClientAPI`)
 * 3) **Vanilla / Modded Scoreboard Teams** (`player.scoreboardTeam` / `isTeammate`)
 *
 * Uses reflection with UUID-based signatures so it compiles with zero external Maven dependencies
 * and never suffers from Mojmap vs Yarn runtime mapping mismatches!
 */
object PartyTeamIntegration {

    data class ServerTeamResolution(
        val teamId: String,
        val teamName: String,
        val teamSystem: String, // "FTB", "OPAC", "Team", or ""
        val memberUuids: Set<UUID>
    )

    data class ClientPartyStatus(
        val activeSystem: String, // "FTB Teams", "Open Parties", "Scoreboard", or "Нет группы"
        val shortBadge: String,   // "FTB", "OPAC", "Тим", or ""
        val partyName: String,
        val memberUuids: Set<UUID>
    )

    private val serverSyncedTeammateFlags = ConcurrentHashMap<UUID, Boolean>()
    private val serverSyncedTeamBadge = ConcurrentHashMap<UUID, String>()
    private val serverSyncedTeamName = ConcurrentHashMap<UUID, String>()

    val isFtbTeamsLoaded: Boolean by lazy {
        FabricLoader.getInstance().isModLoaded("ftbteams")
    }

    val isOpacLoaded: Boolean by lazy {
        FabricLoader.getInstance().isModLoaded("openpartiesandclaims")
    }

    fun recordServerSyncedTeammate(playerUuid: UUID, isTeammate: Boolean, teamSystem: String, teamName: String) {
        if (playerUuid == UUID(0L, 0L)) return
        serverSyncedTeammateFlags[playerUuid] = isTeammate
        if (teamSystem.isNotEmpty()) {
            serverSyncedTeamBadge[playerUuid] = teamSystem
        }
        if (teamName.isNotEmpty()) {
            serverSyncedTeamName[playerUuid] = teamName
        }
    }

    fun clearClientCache() {
        serverSyncedTeammateFlags.clear()
        serverSyncedTeamBadge.clear()
        serverSyncedTeamName.clear()
    }

    // =========================================================================
    // SERVER-SIDE RESOLUTION (FTB Teams + Open Parties and Claims + Scoreboard)
    // =========================================================================

    fun resolveServerTeam(server: MinecraftServer?, player: PlayerEntity?): ServerTeamResolution {
        if (server == null || player == null) {
            return ServerTeamResolution("", "", "", emptySet())
        }
        val playerUuid = player.uuid

        // 1. Check Open Parties and Claims (`xaero.pac.common.server.api.OpenPACServerAPI`)
        resolveOpacServerParty(server, playerUuid)?.let { return it }

        // 2. Check FTB Teams (`dev.ftb.mods.ftbteams.api.FTBTeamsAPI`)
        resolveFtbServerTeam(playerUuid)?.let { return it }

        // 3. Check Vanilla / Modded Scoreboard Team
        val sbTeam = player.scoreboardTeam
        if (sbTeam != null) {
            val teamMembers = mutableSetOf<UUID>()
            teamMembers.add(playerUuid)
            for (online in server.playerManager.playerList) {
                if (online.scoreboardTeam == sbTeam || player.isTeammate(online)) {
                    teamMembers.add(online.uuid)
                }
            }
            return ServerTeamResolution(
                teamId = "sb:${sbTeam.name}",
                teamName = sbTeam.name,
                teamSystem = "Team",
                memberUuids = teamMembers
            )
        }

        return ServerTeamResolution("", "", "", setOf(playerUuid))
    }

    fun areTeammatesOnServer(server: MinecraftServer?, uuid1: UUID, uuid2: UUID): Boolean {
        if (uuid1 == UUID(0L, 0L) || uuid2 == UUID(0L, 0L)) return false
        if (uuid1 == uuid2) return true
        if (server == null) return false

        // 1. Open Parties and Claims server check (party members + allied parties)
        if (checkOpacServerSameOrAlliedParty(server, uuid1, uuid2)) return true

        // 2. FTB Teams server check (same party team + allies)
        if (checkFtbServerSameOrAlliedTeam(uuid1, uuid2)) return true

        // 3. Scoreboard team check
        val p1 = server.playerManager.getPlayer(uuid1)
        val p2 = server.playerManager.getPlayer(uuid2)
        if (p1 != null && p2 != null) {
            if (p1.isTeammate(p2)) return true
            if (p1.scoreboardTeam != null && p1.scoreboardTeam == p2.scoreboardTeam) return true
        }

        return false
    }

    fun getOnlineServerTeammates(server: MinecraftServer?, player: ServerPlayerEntity): List<ServerPlayerEntity> {
        if (server == null) return listOf(player)
        val result = linkedSetOf<ServerPlayerEntity>()
        result.add(player)

        val teamInfo = resolveServerTeam(server, player)
        for (memberUuid in teamInfo.memberUuids) {
            server.playerManager.getPlayer(memberUuid)?.let { result.add(it) }
        }

        for (other in server.playerManager.playerList) {
            if (other.uuid != player.uuid && areTeammatesOnServer(server, player.uuid, other.uuid)) {
                result.add(other)
            }
        }
        return result.toList()
    }

    private fun resolveOpacServerParty(server: MinecraftServer, playerUuid: UUID): ServerTeamResolution? {
        try {
            val apiCls = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI")
            val getMethod = apiCls.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 } ?: return null
            val serverApi = getMethod.invoke(null, server) ?: return null

            val getPartyMgr = serverApi.javaClass.methods.firstOrNull {
                it.name == "getPartyManager" && it.parameterCount == 0
            } ?: return null
            val partyMgr = getPartyMgr.invoke(serverApi) ?: return null

            val getByMember = partyMgr.javaClass.methods.firstOrNull {
                it.name == "getPartyByMember" && it.parameterCount == 1
            }
            val getByOwner = partyMgr.javaClass.methods.firstOrNull {
                it.name == "getPartyByOwner" && it.parameterCount == 1
            }

            val party = getByMember?.invoke(partyMgr, playerUuid)
                ?: getByOwner?.invoke(partyMgr, playerUuid)
                ?: return null

            val partyId = invokeNoArg(party, "getId")?.toString() ?: "opac_party"
            val regName = invokeNoArg(party, "getRegisteredName")?.toString()?.takeIf { it.isNotBlank() }
            val defName = invokeNoArg(party, "getDefaultName")?.toString()?.takeIf { it.isNotBlank() }
            val ownerObj = invokeNoArg(party, "getOwner")
            val ownerName = invokeNoArg(ownerObj, "getUsername")?.toString() ?: "Party"
            val partyName = regName ?: defName ?: "Party $ownerName"

            val members = linkedSetOf<UUID>()
            members.add(playerUuid)
            (invokeNoArg(ownerObj, "getUUID") as? UUID)?.let { members.add(it) }

            val memberStream = invokeNoArg(party, "getMemberInfoStream") as? Stream<*>
            memberStream?.forEach { mInfo ->
                val u = invokeNoArg(mInfo, "getUUID") as? UUID
                if (u != null) members.add(u)
            }

            return ServerTeamResolution(
                teamId = "opac:$partyId",
                teamName = partyName,
                teamSystem = "OPAC",
                memberUuids = members
            )
        } catch (_: Throwable) {
            return null
        }
    }

    private fun checkOpacServerSameOrAlliedParty(server: MinecraftServer, uuid1: UUID, uuid2: UUID): Boolean {
        try {
            val apiCls = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI")
            val getMethod = apiCls.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 } ?: return false
            val serverApi = getMethod.invoke(null, server) ?: return false

            // 1. Check IPlayerPartySystemManagerAPI (bridges all party systems in OPAC)
            val getSysMgr = serverApi.javaClass.methods.firstOrNull {
                it.name == "getPlayerPartySystemManager" && it.parameterCount == 0
            }
            val sysMgr = getSysMgr?.invoke(serverApi)
            if (sysMgr != null) {
                val sameMethod = sysMgr.javaClass.methods.firstOrNull {
                    it.name == "isInSameParty" && it.parameterCount == 2
                }
                if (sameMethod?.invoke(sysMgr, uuid1, uuid2) == true) return true

                val allyMethod = sysMgr.javaClass.methods.firstOrNull {
                    it.name == "isPlayerAllying" && it.parameterCount == 2
                }
                if (allyMethod?.invoke(sysMgr, uuid1, uuid2) == true) return true
            }

            // 2. Direct IPartyManagerAPI check
            val p1 = resolveOpacServerParty(server, uuid1) ?: return false
            if (uuid2 in p1.memberUuids) return true
            val p2 = resolveOpacServerParty(server, uuid2) ?: return false
            if (p1.teamId == p2.teamId) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun resolveFtbServerTeam(playerUuid: UUID): ServerTeamResolution? {
        try {
            val api = getFtbApiInstance() ?: return null
            val isLoaded = invokeNoArg(api, "isManagerLoaded") as? Boolean ?: false
            if (!isLoaded) return null

            val manager = invokeNoArg(api, "getManager") ?: return null
            val getTeamMethod = manager.javaClass.methods.firstOrNull {
                (it.name == "getTeamForPlayerID" || it.name == "getPlayerTeamForPlayerID") && it.parameterCount == 1
            } ?: return null

            val optTeam = getTeamMethod.invoke(manager, playerUuid)
            val team = unwrapOptional(optTeam) ?: return null

            val membersSet = LinkedHashSet<UUID>()
            val rawMembers = invokeNoArg(team, "getMembers") as? Collection<*>
            rawMembers?.forEach { (it as? UUID)?.let { u -> membersSet.add(u) } }
            val isParty = (invokeNoArg(team, "isPartyTeam") as? Boolean) == true || membersSet.size > 1

            // Even if a player is in a solo PlayerTeam with no other members and not a PartyTeam,
            // only mark as an active FTB party if `isParty` is true or they have >1 members.
            if (!isParty && membersSet.size <= 1) return null

            val teamId = (invokeNoArg(team, "getId") ?: invokeNoArg(team, "getTeamId"))?.toString() ?: "ftb_team"
            val nameObj = invokeNoArg(team, "getName")
            val teamName = when (nameObj) {
                is Text -> nameObj.string
                else -> invokeNoArg(team, "getShortName")?.toString() ?: nameObj?.toString() ?: "FTB Team"
            }

            return ServerTeamResolution(
                teamId = "ftb:$teamId",
                teamName = teamName,
                teamSystem = "FTB",
                memberUuids = membersSet
            )
        } catch (_: Throwable) {
            return null
        }
    }

    private fun checkFtbServerSameOrAlliedTeam(uuid1: UUID, uuid2: UUID): Boolean {
        try {
            val api = getFtbApiInstance() ?: return false
            val isLoaded = invokeNoArg(api, "isManagerLoaded") as? Boolean ?: false
            if (!isLoaded) return false

            val manager = invokeNoArg(api, "getManager") ?: return false

            val sameTeamMethod = manager.javaClass.methods.firstOrNull {
                it.name == "arePlayersInSameTeam" && it.parameterCount == 2
            }
            if (sameTeamMethod?.invoke(manager, uuid1, uuid2) == true) return true

            val getTeamMethod = manager.javaClass.methods.firstOrNull {
                it.name == "getTeamForPlayerID" && it.parameterCount == 1
            } ?: return false

            val team1 = unwrapOptional(getTeamMethod.invoke(manager, uuid1)) ?: return false
            val rawMembers = invokeNoArg(team1, "getMembers") as? Collection<*>
            if (rawMembers != null && rawMembers.any { it == uuid2 }) return true

            // Check rank (MEMBER, OFFICER, OWNER, ALLY)
            val rankMethod = team1.javaClass.methods.firstOrNull {
                it.name == "getRankForPlayer" && it.parameterCount == 1
            }
            val rankObj = rankMethod?.invoke(team1, uuid2)
            if (rankObj != null) {
                val isAllyOrBetter = invokeNoArg(rankObj, "isAllyOrBetter") as? Boolean
                if (isAllyOrBetter == true) return true
                val rankName = rankObj.toString().uppercase()
                if (rankName == "MEMBER" || rankName == "OFFICER" || rankName == "OWNER" || rankName == "ALLY") {
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        return false
    }

    // =========================================================================
    // CLIENT-SIDE RESOLUTION (FTB Teams Client + OpenPAC Client + Sync Cache)
    // =========================================================================

    fun isClientTeammate(targetUuid: UUID, localPlayer: PlayerEntity?): Boolean {
        if (targetUuid == UUID(0L, 0L)) return false
        if (localPlayer != null && targetUuid == localPlayer.uuid) return true

        // 1. Check server-synced flag from CombatDamageEvent
        if (serverSyncedTeammateFlags[targetUuid] == true) return true

        // 2. Check client-side Open Parties and Claims (`OpenPACClientAPI`)
        val opacStatus = resolveOpacClientParty(localPlayer?.uuid)
        if (opacStatus != null && targetUuid in opacStatus.memberUuids) return true

        // 3. Check client-side FTB Teams (`FTBTeamsAPI.api().getClientManager()`)
        val ftbStatus = resolveFtbClientParty(localPlayer?.uuid)
        if (ftbStatus != null && targetUuid in ftbStatus.memberUuids) return true

        // 4. Check client-side Scoreboard Team
        if (localPlayer != null) {
            val world = localPlayer.world
            val otherPlayer = world?.players?.firstOrNull { it.uuid == targetUuid }
            if (otherPlayer != null) {
                if (localPlayer.isTeammate(otherPlayer)) return true
                if (localPlayer.scoreboardTeam != null && localPlayer.scoreboardTeam == otherPlayer.scoreboardTeam) {
                    return true
                }
            }
        }

        return false
    }

    fun getTeammateBadge(targetUuid: UUID, localPlayer: PlayerEntity?): String {
        if (localPlayer != null && targetUuid == localPlayer.uuid) return "★"
        if (!isClientTeammate(targetUuid, localPlayer)) return ""

        val syncedSys = serverSyncedTeamBadge[targetUuid]
        if (!syncedSys.isNullOrEmpty()) return syncedSys

        val opacStatus = resolveOpacClientParty(localPlayer?.uuid)
        if (opacStatus != null && targetUuid in opacStatus.memberUuids) return "OPAC"

        val ftbStatus = resolveFtbClientParty(localPlayer?.uuid)
        if (ftbStatus != null && targetUuid in ftbStatus.memberUuids) return "FTB"

        return WowDpsI18n.tr("wowdps.team.party")
    }

    fun getClientPartyStatus(localPlayer: PlayerEntity?): ClientPartyStatus {
        val localUuid = localPlayer?.uuid

        // 1. Open Parties and Claims Client Storage
        resolveOpacClientParty(localUuid)?.let { return it }

        // 2. FTB Teams Client Manager
        resolveFtbClientParty(localUuid)?.let { return it }

        // 3. Server-synced teammates cache
        val syncedAllies = serverSyncedTeammateFlags.filterValues { it }.keys.toMutableSet()
        if (localUuid != null) syncedAllies.add(localUuid)
        if (syncedAllies.size > 1) {
            val badge = serverSyncedTeamBadge.values.firstOrNull { it.isNotEmpty() } ?: WowDpsI18n.tr("wowdps.team.party")
            val tName = serverSyncedTeamName.values.firstOrNull { it.isNotEmpty() } ?: WowDpsI18n.tr("wowdps.team.party")
            val sysFull = when (badge) {
                "FTB" -> "FTB Teams"
                "OPAC" -> "Open Parties"
                else -> WowDpsI18n.tr("wowdps.team.party")
            }
            return ClientPartyStatus(sysFull, badge, tName, syncedAllies)
        }

        // 4. Scoreboard Team on Client
        val sbTeam = localPlayer?.scoreboardTeam
        if (sbTeam != null) {
            val members = linkedSetOf<UUID>()
            members.add(localPlayer.uuid)
            localPlayer.world?.players?.forEach { p ->
                if (p.scoreboardTeam == sbTeam || localPlayer.isTeammate(p)) {
                    members.add(p.uuid)
                }
            }
            return ClientPartyStatus(WowDpsI18n.tr("wowdps.team.scoreboard"), WowDpsI18n.tr("wowdps.team.scoreboard"), sbTeam.name, members)
        }

        // 5. If FTB Teams or OPAC is installed, show ready status even when solo
        val installedLabel = when {
            isFtbTeamsLoaded && isOpacLoaded -> "FTB / OPAC"
            isFtbTeamsLoaded -> "FTB Teams"
            isOpacLoaded -> "Open Parties"
            else -> WowDpsI18n.tr("wowdps.team.solo")
        }
        return ClientPartyStatus(
            activeSystem = installedLabel,
            shortBadge = "",
            partyName = WowDpsI18n.tr("wowdps.team.solo"),
            memberUuids = if (localUuid != null) setOf(localUuid) else emptySet()
        )
    }

    private fun resolveOpacClientParty(localUuid: UUID?): ClientPartyStatus? {
        try {
            val apiCls = Class.forName("xaero.pac.client.api.OpenPACClientAPI")
            val clientApi = invokeStaticNoArg(apiCls, "get") ?: return null
            val partyStorage = invokeNoArg(clientApi, "getPartyStorage") ?: return null
            val party = invokeNoArg(partyStorage, "getParty") ?: return null

            val members = linkedSetOf<UUID>()
            if (localUuid != null) members.add(localUuid)

            val ownerObj = invokeNoArg(party, "getOwner")
            (invokeNoArg(ownerObj, "getUUID") as? UUID)?.let { members.add(it) }

            val memberStream = invokeNoArg(party, "getMemberInfoStream") as? Stream<*>
            memberStream?.forEach { mInfo ->
                (invokeNoArg(mInfo, "getUUID") as? UUID)?.let { members.add(it) }
            }

            val partyName = invokeNoArg(partyStorage, "getPartyName")?.toString()?.takeIf { it.isNotBlank() }
                ?: invokeNoArg(party, "getDefaultName")?.toString()?.takeIf { it.isNotBlank() }
                ?: "Open Party"

            return ClientPartyStatus(
                activeSystem = "Open Parties",
                shortBadge = "OPAC",
                partyName = partyName,
                memberUuids = members
            )
        } catch (_: Throwable) {
            return null
        }
    }

    private fun resolveFtbClientParty(localUuid: UUID?): ClientPartyStatus? {
        try {
            val api = getFtbApiInstance() ?: return null
            val isLoaded = invokeNoArg(api, "isClientManagerLoaded") as? Boolean ?: false
            if (!isLoaded) return null

            val clientMgr = invokeNoArg(api, "getClientManager") ?: return null
            val selfTeam = invokeNoArg(clientMgr, "selfTeam") ?: return null

            val members = linkedSetOf<UUID>()
            if (localUuid != null) members.add(localUuid)

            val rawMembers = invokeNoArg(selfTeam, "getMembers") as? Collection<*>
            rawMembers?.forEach { (it as? UUID)?.let { u -> members.add(u) } }

            // Also scan known client players in ClientTeamManager for any player sharing selfTeam ID or ALLY/MEMBER rank
            val selfTeamId = invokeNoArg(selfTeam, "getId") ?: invokeNoArg(selfTeam, "getTeamId")
            val knownPlayersObj = invokeNoArg(clientMgr, "getKnownPlayers") as? Map<*, *>
            knownPlayersObj?.forEach { (k, v) ->
                val pUuid = k as? UUID
                if (pUuid != null && v != null) {
                    val kpTeamId = invokeNoArg(v, "teamId") ?: invokeNoArg(v, "getTeamId")
                    if (selfTeamId != null && kpTeamId == selfTeamId) {
                        members.add(pUuid)
                    }
                }
            }

            val rankMethod = selfTeam.javaClass.methods.firstOrNull {
                it.name == "getRankForPlayer" && it.parameterCount == 1
            }
            if (rankMethod != null && knownPlayersObj != null) {
                for (k in knownPlayersObj.keys) {
                    val pUuid = k as? UUID ?: continue
                    val rankObj = rankMethod.invoke(selfTeam, pUuid) ?: continue
                    val isAlly = (invokeNoArg(rankObj, "isAllyOrBetter") as? Boolean) == true
                    val rName = rankObj.toString().uppercase()
                    if (isAlly || rName == "MEMBER" || rName == "OFFICER" || rName == "OWNER" || rName == "ALLY") {
                        members.add(pUuid)
                    }
                }
            }

            val isParty = (invokeNoArg(selfTeam, "isPartyTeam") as? Boolean) == true || members.size > 1
            if (!isParty && members.size <= 1) return null

            val nameObj = invokeNoArg(selfTeam, "getName")
            val teamName = when (nameObj) {
                is Text -> nameObj.string
                else -> invokeNoArg(selfTeam, "getShortName")?.toString() ?: nameObj?.toString() ?: "FTB Party"
            }

            return ClientPartyStatus(
                activeSystem = "FTB Teams",
                shortBadge = "FTB",
                partyName = teamName,
                memberUuids = members
            )
        } catch (_: Throwable) {
            return null
        }
    }

    private fun getFtbApiInstance(): Any? {
        for (clsName in listOf("dev.ftb.mods.ftbteams.api.FTBTeamsAPI", "dev.ftb.mods.ftbteams.FTBTeamsAPI")) {
            try {
                val cls = Class.forName(clsName)
                val api = invokeStaticNoArg(cls, "api")
                if (api != null) return api
                return cls
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun invokeStaticNoArg(cls: Class<*>, methodName: String): Any? {
        val m = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return null
        return m.invoke(null)
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return null
        return m.invoke(target)
    }

    private fun unwrapOptional(obj: Any?): Any? {
        if (obj is Optional<*>) return obj.orElse(null)
        return obj
    }
}
