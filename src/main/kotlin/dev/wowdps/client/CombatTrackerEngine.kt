package dev.wowdps.client

import dev.wowdps.network.CombatDamageEvent
import dev.wowdps.team.PartyTeamIntegration
import net.minecraft.client.MinecraftClient
import net.minecraft.client.resource.language.I18n
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class MeterMode(val keyPrefix: String, val defaultFull: String, val defaultShort: String) {
    DPS("wowdps.mode.dps", "DPS", "DPS"),
    TOTAL_DAMAGE("wowdps.mode.total", "Total Damage", "Damage"),
    TARGETS("wowdps.mode.targets", "Damage by Target", "Targets"),
    BURST_DPS("wowdps.mode.burst", "Peak Burst DPS", "Burst"),
    DAMAGE_TAKEN("wowdps.mode.taken", "Damage Taken", "Taken");

    val displayName: String
        get() = WowDpsI18n.tr("$keyPrefix.full").takeIf { it != "$keyPrefix.full" } ?: defaultFull

    val shortName: String
        get() = WowDpsI18n.tr("$keyPrefix.short").takeIf { it != "$keyPrefix.short" } ?: defaultShort

    fun next(): MeterMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Filter mode for FTB Teams & Open Parties and Claims teammates!
 */
enum class GroupFilterMode(val keyPrefix: String, val defaultFull: String, val defaultShort: String) {
    ALL("wowdps.filter.all", "All Players", "All"),
    PARTY_ONLY("wowdps.filter.party", "Party Only (FTB/OPAC)", "Party"),
    SELF_ONLY("wowdps.filter.self", "Only Me", "Me");

    val displayName: String
        get() = WowDpsI18n.tr("$keyPrefix.full").takeIf { it != "$keyPrefix.full" } ?: defaultFull

    val shortName: String
        get() = WowDpsI18n.tr("$keyPrefix.short").takeIf { it != "$keyPrefix.short" } ?: defaultShort

    fun next(): GroupFilterMode = entries[(ordinal + 1) % entries.size]
}

enum class SegmentView {
    CURRENT,
    OVERALL,
    HISTORY_0,
    HISTORY_1,
    HISTORY_2;

    fun label(engine: CombatTrackerEngine): String = when (this) {
        CURRENT -> WowDpsI18n.tr("wowdps.segment.current")
        OVERALL -> WowDpsI18n.tr("wowdps.segment.overall")
        HISTORY_0 -> engine.getHistorySegment(0)?.title ?: WowDpsI18n.tr("wowdps.segment.fight", 1)
        HISTORY_1 -> engine.getHistorySegment(1)?.title ?: WowDpsI18n.tr("wowdps.segment.fight", 2)
        HISTORY_2 -> engine.getHistorySegment(2)?.title ?: WowDpsI18n.tr("wowdps.segment.fight", 3)
    }
}

enum class InspectorTab(val key: String, val defaultTitle: String) {
    GRAPHS("wowdps.tab.graphs", "Graphs"),
    ABILITIES("wowdps.tab.abilities", "Spells"),
    WEAPON_TREE("wowdps.tab.weapon_tree", "Breakdown"),
    TARGETS("wowdps.tab.targets", "Targets");

    val title: String
        get() = WowDpsI18n.tr(key).takeIf { it != key } ?: defaultTitle
}

enum class GraphType(val key: String, val defaultTitle: String) {
    BAR_TIMELINE("wowdps.graph.bar_timeline", "1. Bar DPS Timeline"),
    AREA_CURVE("wowdps.graph.area_curve", "2. Area Curve"),
    DONUT_PIE("wowdps.graph.donut_pie", "3. Donut Pie"),
    HIT_HISTOGRAM("wowdps.graph.hit_histogram", "4. Hit Histogram");

    val title: String
        get() = WowDpsI18n.tr(key).takeIf { it != key } ?: defaultTitle

    fun next(): GraphType = entries[(ordinal + 1) % entries.size]
}

data class AbilityStats(
    val abilityName: String,
    var spellId: String,
    var iconItemId: String,
    var spellTextureId: String,
    var magicSchool: String,
    var iconHasGlint: Boolean,
    val damageTypeId: String,
    var sourceWeaponName: String = "",
    var sourceWeaponIconId: String = "minecraft:iron_sword",
    var hits: Int = 0,
    var crits: Int = 0,
    var petHits: Int = 0,
    var totalDamage: Double = 0.0,
    var mitigatedDamage: Double = 0.0,
    var overkillDamage: Double = 0.0,
    var minHit: Float = Float.MAX_VALUE,
    var maxHit: Float = 0f,
    var lastTargetHit: String = ""
) {
    val critRatePercent: Double
        get() = if (hits > 0) (crits.toDouble() / hits.toDouble()) * 100.0 else 0.0

    val avgHit: Double
        get() = if (hits > 0) totalDamage / hits.toDouble() else 0.0
}

/**
 * Hierarchical Weapon / Staff / Sword Breakdown:
 * Shows a weapon (e.g. "Посох Огня" or "Пламенный меч") and the nested list of all
 * spells, projectiles, and strikes cast/released from that weapon!
 */
class WeaponSourceBreakdown(
    val weaponName: String,
    var weaponIconId: String,
    var weaponGlint: Boolean,
    var expanded: Boolean = true
) {
    var totalDamage: Double = 0.0
    var totalHits: Int = 0
    var totalCrits: Int = 0
    val releasedSpells = linkedMapOf<String, AbilityStats>()

    fun getSortedSpells(): List<AbilityStats> {
        return releasedSpells.values.sortedByDescending { it.totalDamage }
    }
}

data class TargetDamageStats(
    val targetName: String,
    val targetTypeName: String,
    var targetEntityTypeId: String,
    var targetIconItemId: String,
    val isBoss: Boolean,
    var hits: Int = 0,
    var crits: Int = 0,
    var totalDamage: Double = 0.0,
    var mitigatedDamage: Double = 0.0,
    var minHit: Float = Float.MAX_VALUE,
    var maxHit: Float = 0f,
    var lastWeaponUsed: String = "",
    var lastWeaponIconId: String = "minecraft:iron_sword",
    var lastSpellTextureId: String = "",
    var lastMagicSchool: String = "physical"
) {
    val critRatePercent: Double
        get() = if (hits > 0) (crits.toDouble() / hits.toDouble()) * 100.0 else 0.0

    val avgHit: Double
        get() = if (hits > 0) totalDamage / hits.toDouble() else 0.0
}

data class TimelinePoint(
    val secondIndex: Int,
    var damageInSecond: Double = 0.0,
    var critDamageInSecond: Double = 0.0,
    var mitigatedInSecond: Double = 0.0,
    var cumulativeDps: Double = 0.0,
    var critHitsInSecond: Int = 0,
    var topAbilityInSecond: String = "",
    var targetInSecond: String = ""
)

class CombatantStats(
    val uuid: UUID,
    var name: String,
    val classColorRgb: Int
) {
    var totalDamageDealt: Double = 0.0
    var totalDamageTaken: Double = 0.0
    var totalMitigated: Double = 0.0
    var totalOverkill: Double = 0.0
    var totalHits: Int = 0
    var totalCrits: Int = 0
    var peakBurstDps: Double = 0.0
    var firstActionMs: Long = 0L
    var lastActionMs: Long = 0L

    var teamId: String = ""
    var teamName: String = ""
    var teamSystem: String = ""
    var isServerTeammate: Boolean = false

    var lastAbilityName: String = WowDpsI18n.tr("wowdps.attack.generic")
    var lastIconItemId: String = "minecraft:iron_sword"
    var lastSpellTextureId: String = ""
    var lastMagicSchool: String = "physical"
    var lastIconHasGlint: Boolean = false
    var lastSourceWeaponName: String = ""
    var lastTargetName: String = ""
    var lastTargetEntityTypeId: String = ""
    var lastTargetIconId: String = "minecraft:armor_stand"

    private val recentHitsWindow = ArrayDeque<Pair<Long, Float>>()
    val abilities = linkedMapOf<String, AbilityStats>()
    val weaponBreakdowns = linkedMapOf<String, WeaponSourceBreakdown>()
    val targets = linkedMapOf<String, TargetDamageStats>()
    private val secondBuckets = linkedMapOf<Int, TimelinePoint>()

    fun isLocalPlayer(): Boolean {
        val localUuid = MinecraftClient.getInstance().player?.uuid ?: return false
        return uuid == localUuid
    }

    fun isTeammateOrSelf(): Boolean {
        val localPlayer = MinecraftClient.getInstance().player ?: return true
        if (uuid == localPlayer.uuid) return true
        if (isServerTeammate) return true
        return PartyTeamIntegration.isClientTeammate(uuid, localPlayer)
    }

    fun getPartyBadge(): String {
        val localPlayer = MinecraftClient.getInstance().player
        if (localPlayer != null && uuid == localPlayer.uuid) return "★"
        if (teamSystem.isNotEmpty() && isTeammateOrSelf()) return teamSystem
        return PartyTeamIntegration.getTeammateBadge(uuid, localPlayer)
    }

    fun recordDamageDealt(event: CombatDamageEvent, segmentStartMs: Long) {
        val dmg = event.effectiveDamage.toDouble()
        if (firstActionMs == 0L) firstActionMs = event.timestampMs
        lastActionMs = event.timestampMs

        if (event.teamId.isNotEmpty()) teamId = event.teamId
        if (event.teamName.isNotEmpty()) teamName = event.teamName
        if (event.teamSystem.isNotEmpty()) teamSystem = event.teamSystem
        if (event.isTeammate) isServerTeammate = true

        val resolvedSpellName = CombatTrackerEngine.resolveClientSpellName(event.spellId, WowDpsI18n.resolveServerText(event.abilityId))

        totalDamageDealt += dmg
        totalMitigated += event.mitigatedDamage.toDouble()
        totalOverkill += event.overkillDamage.toDouble()
        totalHits++
        if (event.isCritical) totalCrits++

        lastAbilityName = resolvedSpellName
        lastIconItemId = event.iconItemId
        lastSpellTextureId = event.spellTextureId
        lastMagicSchool = event.magicSchool
        lastIconHasGlint = event.iconHasGlint
        lastSourceWeaponName = WowDpsI18n.resolveServerText(event.sourceWeaponName)
        lastTargetName = event.victimName
        lastTargetEntityTypeId = event.victimEntityTypeId
        lastTargetIconId = event.targetIconItemId

        recentHitsWindow.addLast(event.timestampMs to event.effectiveDamage)
        val cutoff = event.timestampMs - 5000L
        while (recentHitsWindow.isNotEmpty() && recentHitsWindow.peekFirst().first < cutoff) {
            recentHitsWindow.removeFirst()
        }
        val windowSum = recentHitsWindow.sumOf { it.second.toDouble() }
        val windowSpanSec = max(1.0, (event.timestampMs - (recentHitsWindow.peekFirst()?.first ?: event.timestampMs)) / 1000.0)
        val currentBurst = windowSum / max(2.0, windowSpanSec)
        if (currentBurst > peakBurstDps) {
            peakBurstDps = currentBurst
        }

        // 1. Record into primary Spells / Abilities list (keyed by the exact spell name!)
        val ability = abilities.getOrPut(resolvedSpellName) {
            AbilityStats(
                abilityName = resolvedSpellName,
                spellId = event.spellId,
                iconItemId = event.iconItemId,
                spellTextureId = event.spellTextureId,
                magicSchool = event.magicSchool,
                iconHasGlint = event.iconHasGlint,
                damageTypeId = event.damageTypeId,
                sourceWeaponName = WowDpsI18n.resolveServerText(event.sourceWeaponName),
                sourceWeaponIconId = event.sourceWeaponIconId
            )
        }
        ability.iconItemId = event.iconItemId
        if (event.spellTextureId.isNotEmpty()) ability.spellTextureId = event.spellTextureId
        if (event.magicSchool != "physical") ability.magicSchool = event.magicSchool
        if (event.sourceWeaponName.isNotEmpty()) {
            ability.sourceWeaponName = WowDpsI18n.resolveServerText(event.sourceWeaponName)
            ability.sourceWeaponIconId = event.sourceWeaponIconId
        }
        ability.iconHasGlint = ability.iconHasGlint || event.iconHasGlint
        ability.hits++
        if (event.isCritical) ability.crits++
        if (event.viaPetOrSummon) ability.petHits++
        ability.totalDamage += dmg
        ability.mitigatedDamage += event.mitigatedDamage.toDouble()
        ability.overkillDamage += event.overkillDamage.toDouble()
        ability.minHit = min(ability.minHit, event.effectiveDamage)
        ability.maxHit = max(ability.maxHit, event.effectiveDamage)
        ability.lastTargetHit = event.victimName

        // 2. Record into Hierarchical Weapon -> Spells Breakdown ("Развёртка посоха/меча -> магия")
        val wName = WowDpsI18n.resolveServerText(event.sourceWeaponName).ifEmpty { WowDpsI18n.tr("wowdps.wpn.default_group") }
        val weaponGroup = weaponBreakdowns.getOrPut(wName) {
            WeaponSourceBreakdown(
                weaponName = wName,
                weaponIconId = event.sourceWeaponIconId,
                weaponGlint = event.sourceWeaponGlint
            )
        }
        weaponGroup.weaponIconId = event.sourceWeaponIconId
        weaponGroup.weaponGlint = weaponGroup.weaponGlint || event.sourceWeaponGlint
        weaponGroup.totalDamage += dmg
        weaponGroup.totalHits++
        if (event.isCritical) weaponGroup.totalCrits++

        val nestedSpell = weaponGroup.releasedSpells.getOrPut(resolvedSpellName) {
            AbilityStats(
                abilityName = resolvedSpellName,
                spellId = event.spellId,
                iconItemId = event.iconItemId,
                spellTextureId = event.spellTextureId,
                magicSchool = event.magicSchool,
                iconHasGlint = event.iconHasGlint,
                damageTypeId = event.damageTypeId,
                sourceWeaponName = wName,
                sourceWeaponIconId = event.sourceWeaponIconId
            )
        }
        nestedSpell.iconItemId = event.iconItemId
        if (event.spellTextureId.isNotEmpty()) nestedSpell.spellTextureId = event.spellTextureId
        if (event.magicSchool != "physical") nestedSpell.magicSchool = event.magicSchool
        nestedSpell.hits++
        if (event.isCritical) nestedSpell.crits++
        nestedSpell.totalDamage += dmg
        nestedSpell.mitigatedDamage += event.mitigatedDamage.toDouble()
        nestedSpell.minHit = min(nestedSpell.minHit, event.effectiveDamage)
        nestedSpell.maxHit = max(nestedSpell.maxHit, event.effectiveDamage)
        nestedSpell.lastTargetHit = event.victimName

        // 3. Record into Targets list
        val targetKey = "${event.victimName}|${event.victimEntityTypeId}"
        val targetStat = targets.getOrPut(targetKey) {
            TargetDamageStats(
                targetName = event.victimName,
                targetTypeName = WowDpsI18n.resolveServerText(event.victimTypeName),
                targetEntityTypeId = event.victimEntityTypeId,
                targetIconItemId = event.targetIconItemId,
                isBoss = event.victimIsBoss
            )
        }
        targetStat.targetEntityTypeId = event.victimEntityTypeId
        targetStat.targetIconItemId = event.targetIconItemId
        targetStat.hits++
        if (event.isCritical) targetStat.crits++
        targetStat.totalDamage += dmg
        targetStat.mitigatedDamage += event.mitigatedDamage.toDouble()
        targetStat.minHit = min(targetStat.minHit, event.effectiveDamage)
        targetStat.maxHit = max(targetStat.maxHit, event.effectiveDamage)
        targetStat.lastWeaponUsed = resolvedSpellName
        targetStat.lastWeaponIconId = event.iconItemId
        targetStat.lastSpellTextureId = event.spellTextureId
        targetStat.lastMagicSchool = event.magicSchool

        val secIdx = max(0, ((event.timestampMs - segmentStartMs) / 1000L).toInt()).coerceAtMost(3600)
        val point = secondBuckets.getOrPut(secIdx) { TimelinePoint(secIdx) }
        point.damageInSecond += dmg
        if (event.isCritical) {
            point.critHitsInSecond++
            point.critDamageInSecond += dmg
        }
        point.mitigatedInSecond += event.mitigatedDamage.toDouble()
        point.topAbilityInSecond = resolvedSpellName
        point.targetInSecond = event.victimName
        point.cumulativeDps = totalDamageDealt / max(1.0, (secIdx + 1).toDouble())
    }

    fun recordDamageTaken(event: CombatDamageEvent) {
        if (firstActionMs == 0L) firstActionMs = event.timestampMs
        lastActionMs = event.timestampMs
        totalDamageTaken += event.effectiveDamage.toDouble()
    }

    val critPercent: Double
        get() = if (totalHits > 0) (totalCrits.toDouble() / totalHits.toDouble()) * 100.0 else 0.0

    fun getDps(segmentDurationSeconds: Double): Double {
        return totalDamageDealt / max(1.0, segmentDurationSeconds)
    }

    fun getMetricValue(mode: MeterMode, segmentDurationSeconds: Double): Double = when (mode) {
        MeterMode.DPS -> getDps(segmentDurationSeconds)
        MeterMode.TOTAL_DAMAGE, MeterMode.TARGETS -> totalDamageDealt
        MeterMode.BURST_DPS -> peakBurstDps
        MeterMode.DAMAGE_TAKEN -> totalDamageTaken
    }

    fun getPrimaryAbility(): AbilityStats? {
        return abilities.values.maxByOrNull { it.totalDamage }
    }

    fun getSortedAbilities(): List<AbilityStats> {
        return abilities.values.sortedByDescending { it.totalDamage }
    }

    fun getSortedWeapons(): List<WeaponSourceBreakdown> {
        return weaponBreakdowns.values.sortedByDescending { it.totalDamage }
    }

    fun getSortedTargets(): List<TargetDamageStats> {
        return targets.values.sortedByDescending { it.totalDamage }
    }

    fun getTimelineSeries(maxSeconds: Int): List<TimelinePoint> {
        val maxRecordedSec = secondBuckets.keys.maxOrNull() ?: 0
        val totalSpan = max(6, min(maxSeconds, maxRecordedSec + 1))
        val startSec = max(0, (maxRecordedSec + 1) - totalSpan)

        val result = ArrayList<TimelinePoint>(totalSpan)
        var runningDamage = 0.0
        for (s in 0..maxRecordedSec) {
            val bucket = secondBuckets[s]
            if (bucket != null) {
                runningDamage += bucket.damageInSecond
            }
            if (s >= startSec) {
                val avgDps = runningDamage / max(1.0, (s + 1).toDouble())
                result.add(
                    TimelinePoint(
                        secondIndex = s,
                        damageInSecond = bucket?.damageInSecond ?: 0.0,
                        critDamageInSecond = bucket?.critDamageInSecond ?: 0.0,
                        mitigatedInSecond = bucket?.mitigatedInSecond ?: 0.0,
                        cumulativeDps = avgDps,
                        critHitsInSecond = bucket?.critHitsInSecond ?: 0,
                        topAbilityInSecond = bucket?.topAbilityInSecond ?: "",
                        targetInSecond = bucket?.targetInSecond ?: ""
                    )
                )
            }
        }
        while (result.size < 6) {
            val nextIdx = result.size
            result.add(TimelinePoint(nextIdx))
        }
        return result
    }
}

class CombatSegment(
    var title: String,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    var endTimeMs: Long = startTimeMs
    var inCombat: Boolean = false
    var primaryTargetName: String = ""
        get() = field.ifEmpty { WowDpsI18n.tr("wowdps.hud.waiting_short") }
    var primaryTargetEntityTypeId: String = ""
    var primaryTargetIconId: String = "minecraft:armor_stand"
    var isBossEncounter: Boolean = false

    val combatants = linkedMapOf<UUID, CombatantStats>()
    val allTargets = linkedMapOf<String, TargetDamageStats>()

    val durationSeconds: Double
        get() {
            if (combatants.isEmpty()) return 0.0
            val effectiveEnd = if (inCombat) System.currentTimeMillis() else endTimeMs
            return max(1.0, (effectiveEnd - startTimeMs) / 1000.0)
        }

    fun recordSegmentTarget(event: CombatDamageEvent) {
        val key = "${event.victimName}|${event.victimEntityTypeId}"
        val t = allTargets.getOrPut(key) {
            TargetDamageStats(
                targetName = event.victimName,
                targetTypeName = WowDpsI18n.resolveServerText(event.victimTypeName),
                targetEntityTypeId = event.victimEntityTypeId,
                targetIconItemId = event.targetIconItemId,
                isBoss = event.victimIsBoss
            )
        }
        t.targetEntityTypeId = event.victimEntityTypeId
        t.targetIconItemId = event.targetIconItemId
        t.hits++
        if (event.isCritical) t.crits++
        t.totalDamage += event.effectiveDamage.toDouble()
        t.mitigatedDamage += event.mitigatedDamage.toDouble()
        t.minHit = min(t.minHit, event.effectiveDamage)
        t.maxHit = max(t.maxHit, event.effectiveDamage)
        t.lastWeaponUsed = CombatTrackerEngine.resolveClientSpellName(event.spellId, event.abilityId)
        t.lastWeaponIconId = event.iconItemId
        t.lastSpellTextureId = event.spellTextureId
        t.lastMagicSchool = event.magicSchool
    }

    fun getSortedSegmentTargets(): List<TargetDamageStats> {
        return allTargets.values.sortedByDescending { it.totalDamage }
    }

    fun getTotalGroupMetric(mode: MeterMode, filter: GroupFilterMode = CombatTrackerEngine.groupFilterMode): Double {
        val dur = durationSeconds
        return getSortedCombatants(mode, filter).sumOf { it.getMetricValue(mode, dur) }
    }

    fun getSortedCombatants(
        mode: MeterMode,
        filter: GroupFilterMode = CombatTrackerEngine.groupFilterMode
    ): List<CombatantStats> {
        val dur = durationSeconds
        return combatants.values
            .filter { c ->
                val hasValue = when (mode) {
                    MeterMode.DAMAGE_TAKEN -> c.totalDamageTaken > 0.0
                    else -> c.totalDamageDealt > 0.0
                }
                if (!hasValue) return@filter false
                when (filter) {
                    GroupFilterMode.ALL -> true
                    GroupFilterMode.PARTY_ONLY -> c.isTeammateOrSelf()
                    GroupFilterMode.SELF_ONLY -> c.isLocalPlayer()
                }
            }
            .sortedByDescending { it.getMetricValue(mode, dur) }
    }
}

object CombatTrackerEngine {

    val CHART_SLICE_COLORS = intArrayOf(
        0xFF3B82F6.toInt(), // Blue
        0xFFF59E0B.toInt(), // Amber / Gold
        0xFF10B981.toInt(), // Emerald Green
        0xFFEC4899.toInt(), // Pink
        0xFF8B5CF6.toInt(), // Purple
        0xFFEF4444.toInt(), // Red
        0xFF06B6D4.toInt(), // Cyan
        0xFFF97316.toInt()  // Orange
    )

    private val WOW_CLASS_PALETTE = intArrayOf(
        0xC79C6E, // Warrior (Brown)
        0xF58CBA, // Paladin (Pink)
        0xABD473, // Hunter (Green)
        0xFFF569, // Rogue (Yellow)
        0x69CCF0, // Mage (Light Blue)
        0x9482C9, // Warlock (Purple)
        0xFF7D0A, // Druid (Orange)
        0x0070DE, // Shaman (Deep Blue)
        0xC41F3B, // Death Knight (Red)
        0x00FF96, // Monk (Jade Green)
        0xA330C9  // Demon Hunter (Dark Magenta)
    )

    var combatTimeoutMs: Long = 6500L
    var currentMode: MeterMode = MeterMode.DPS
    var groupFilterMode: GroupFilterMode = GroupFilterMode.ALL
    var selectedSegmentView: SegmentView = SegmentView.CURRENT
    var activeInspectorTab: InspectorTab = InspectorTab.WEAPON_TREE
    var activeGraphType: GraphType = GraphType.BAR_TIMELINE

    var hudVisible: Boolean = true
    var hudCollapsed: Boolean = false

    // Small HUD window exact pixel dimensions (freely resizable in both X and Y just like the big Inspector window!)
    var hudX: Int = 8
    var hudY: Int = 8
    var hudWidth: Int = 225
    var hudHeight: Int = 115
    var uiScale: Float = 0.85f

    // Big Inspector window dimensions
    var inspectorX: Int = -1
    var inspectorY: Int = 36
    var inspectorW: Int = 360
    var inspectorH: Int = 220

    var overallSegment = CombatSegment("Overall")
        private set

    var currentSegment = CombatSegment("Waiting...")
        private set

    private val fightHistory = ArrayDeque<CombatSegment>()
    private var lastCombatEventMs: Long = 0L
    var serverSyncActive: Boolean = false
        private set

    private val clientEntityHealthCache = HashMap<Int, Float>()
    private val iconStackCache = HashMap<String, ItemStack>()
    private val previewEntityCache = HashMap<String, LivingEntity?>()
    private var lastPreviewWorldHash: Int = 0

    fun resolveClientSpellName(spellId: String, serverAbilityName: String): String {
        if (spellId.isEmpty() || spellId.startsWith("spell_power:")) return serverAbilityName
        val id = Identifier.tryParse(spellId) ?: return serverAbilityName
        val candidateKeys = listOf(
            "spell.${id.namespace}.${id.path}.name",
            "spell.${id.namespace}.${id.path}",
            "effect.${id.namespace}.${id.path}"
        )
        for (key in candidateKeys) {
            if (I18n.hasTranslation(key)) {
                val translated = I18n.translate(key)
                if (translated.isNotBlank() && translated != key) {
                    return translated
                }
            }
        }
        return WowDpsI18n.resolveServerText(serverAbilityName)
    }

    fun getIconStack(iconItemId: String, hasGlint: Boolean): ItemStack {
        val key = "$iconItemId|$hasGlint"
        return iconStackCache.getOrPut(key) {
            val id = Identifier.tryParse(iconItemId) ?: Identifier("minecraft", "iron_sword")
            val item = Registries.ITEM.getOrEmpty(id).orElse(Items.IRON_SWORD)
            val stack = ItemStack(item)
            if (hasGlint) {
                stack.addEnchantment(Enchantments.UNBREAKING, 1)
            }
            stack
        }
    }

    fun getOrCreatePreviewEntity(entityTypeId: String): LivingEntity? {
        if (entityTypeId.isEmpty()) return null
        val world = MinecraftClient.getInstance().world ?: return null
        val worldHash = System.identityHashCode(world)
        if (worldHash != lastPreviewWorldHash) {
            previewEntityCache.clear()
            lastPreviewWorldHash = worldHash
        }
        return previewEntityCache.getOrPut(entityTypeId) {
            try {
                val id = Identifier.tryParse(entityTypeId) ?: return@getOrPut null
                val type: EntityType<*> = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null) ?: return@getOrPut null
                type.create(world) as? LivingEntity
            } catch (_: Exception) {
                null
            }
        }
    }

    fun adjustScale(delta: Float) {
        uiScale = (uiScale + delta).coerceIn(0.65f, 1.30f)
    }

    fun onServerDamagePacket(event: CombatDamageEvent) {
        serverSyncActive = true
        PartyTeamIntegration.recordServerSyncedTeammate(
            playerUuid = event.attackerUuid,
            isTeammate = event.isTeammate,
            teamSystem = event.teamSystem,
            teamName = event.teamName
        )
        processDamageEvent(event)
    }

    fun processDamageEvent(event: CombatDamageEvent) {
        val now = System.currentTimeMillis()

        // Strict combat state trigger:
        // Combat mode activates ONLY when damage was inflicted by or against a Living Entity (Mob, Boss, Player)!
        // Passive/environmental damage to the player (drowning, fall, suffocation, burning without mob) does NOT activate combat mode.
        val shouldTriggerCombat = event.isCombatTrigger

        if (shouldTriggerCombat) {
            if (!currentSegment.inCombat) {
                if (currentSegment.combatants.isNotEmpty()) {
                    archiveCurrentSegment()
                }
                currentSegment = CombatSegment(
                    title = event.victimName,
                    startTimeMs = now
                ).apply {
                    inCombat = true
                    primaryTargetName = event.victimName
                    primaryTargetEntityTypeId = event.victimEntityTypeId
                    primaryTargetIconId = event.targetIconItemId
                    isBossEncounter = event.victimIsBoss
                }
            } else {
                currentSegment.primaryTargetName = event.victimName
                currentSegment.primaryTargetEntityTypeId = event.victimEntityTypeId
                currentSegment.primaryTargetIconId = event.targetIconItemId
                if (event.victimIsBoss && !currentSegment.isBossEncounter) {
                    currentSegment.isBossEncounter = true
                    currentSegment.title = WowDpsI18n.tr("wowdps.attack.boss_prefix", event.victimName)
                }
            }

            if (!overallSegment.inCombat) {
                overallSegment.inCombat = true
            }
            overallSegment.primaryTargetName = event.victimName
            overallSegment.primaryTargetEntityTypeId = event.victimEntityTypeId
            overallSegment.primaryTargetIconId = event.targetIconItemId

            lastCombatEventMs = now
        }

        currentSegment.endTimeMs = now
        overallSegment.endTimeMs = now

        if (event.attackerUuid != UUID(0L, 0L)) {
            currentSegment.recordSegmentTarget(event)
            overallSegment.recordSegmentTarget(event)

            val curAttacker = getOrCreateCombatant(currentSegment, event.attackerUuid, event.attackerName)
            curAttacker.recordDamageDealt(event, currentSegment.startTimeMs)

            val ovrAttacker = getOrCreateCombatant(overallSegment, event.attackerUuid, event.attackerName)
            ovrAttacker.recordDamageDealt(event, overallSegment.startTimeMs)
        }

        val clientWorld = MinecraftClient.getInstance().world
        val isVictimPlayer = clientWorld?.players?.any { it.uuid == event.victimUuid } == true
        if (isVictimPlayer) {
            val curVictim = getOrCreateCombatant(currentSegment, event.victimUuid, event.victimName)
            curVictim.recordDamageTaken(event)

            val ovrVictim = getOrCreateCombatant(overallSegment, event.victimUuid, event.victimName)
            ovrVictim.recordDamageTaken(event)
        }
    }

    fun tickClient(client: MinecraftClient) {
        val now = System.currentTimeMillis()
        if (currentSegment.inCombat && (now - lastCombatEventMs) > combatTimeoutMs) {
            currentSegment.inCombat = false
            currentSegment.endTimeMs = lastCombatEventMs
            overallSegment.inCombat = false
        }

        if (!serverSyncActive && client.world != null && client.player != null) {
            tickClientFallbackEstimation(client, now)
        }
    }

    private fun tickClientFallbackEstimation(client: MinecraftClient, now: Long) {
        val world = client.world ?: return
        val localPlayer = client.player ?: return

        for (entity in world.entities) {
            if (entity is LivingEntity && entity != localPlayer) {
                val prevHp = clientEntityHealthCache[entity.id]
                val curHp = entity.health + entity.absorptionAmount
                if (prevHp != null && curHp < prevHp && entity.squaredDistanceTo(localPlayer) <= 36.0 &&
                    (localPlayer.handSwinging || localPlayer.handSwingProgress > 0f)
                ) {
                    val diff = prevHp - curHp
                    val mainHand = localPlayer.mainHandStack
                    val weaponName = mainHand.takeIf { !it.isEmpty }?.name?.string ?: WowDpsI18n.tr("wowdps.attack.hand")
                    val iconId = if (!mainHand.isEmpty) {
                        Registries.ITEM.getId(mainHand.item).toString()
                    } else {
                        "minecraft:iron_sword"
                    }
                    val typeId = Registries.ENTITY_TYPE.getId(entity.type).toString()
                    val fallbackEvent = CombatDamageEvent(
                        timestampMs = now,
                        attackerUuid = localPlayer.uuid,
                        attackerName = localPlayer.name.string,
                        victimUuid = entity.uuid,
                        victimName = entity.name.string,
                        victimTypeName = entity.type.name.string,
                        victimEntityTypeId = typeId,
                        targetIconItemId = "minecraft:skeleton_skull",
                        victimIsBoss = entity.maxHealth >= 100f,
                        spellId = "",
                        abilityId = WowDpsI18n.tr("wowdps.attack.melee", weaponName),
                        iconItemId = iconId,
                        spellTextureId = "",
                        magicSchool = "physical",
                        iconHasGlint = mainHand.hasGlint(),
                        sourceWeaponName = weaponName,
                        sourceWeaponIconId = iconId,
                        sourceWeaponGlint = mainHand.hasGlint(),
                        damageTypeId = "client:estimated",
                        rawAmount = diff,
                        effectiveDamage = diff,
                        mitigatedDamage = 0f,
                        overkillDamage = 0f,
                        isCritical = localPlayer.fallDistance > 0f && !localPlayer.isOnGround,
                        viaPetOrSummon = false,
                        isTeammate = true
                    )
                    processDamageEvent(fallbackEvent)
                }
                clientEntityHealthCache[entity.id] = curHp
            }
        }
    }

    private fun getOrCreateCombatant(segment: CombatSegment, uuid: UUID, name: String): CombatantStats {
        return segment.combatants.getOrPut(uuid) {
            val colorIndex = (uuid.hashCode() and 0x7FFFFFFF) % WOW_CLASS_PALETTE.size
            CombatantStats(uuid, name, WOW_CLASS_PALETTE[colorIndex])
        }.also {
            it.name = name
        }
    }

    private fun archiveCurrentSegment() {
        currentSegment.inCombat = false
        fightHistory.addFirst(currentSegment)
        while (fightHistory.size > 5) {
            fightHistory.removeLast()
        }
    }

    fun getActiveSegment(): CombatSegment = when (selectedSegmentView) {
        SegmentView.CURRENT -> currentSegment
        SegmentView.OVERALL -> overallSegment
        SegmentView.HISTORY_0 -> getHistorySegment(0) ?: currentSegment
        SegmentView.HISTORY_1 -> getHistorySegment(1) ?: currentSegment
        SegmentView.HISTORY_2 -> getHistorySegment(2) ?: currentSegment
    }

    fun getHistorySegment(index: Int): CombatSegment? {
        return fightHistory.elementAtOrNull(index)
    }

    fun cycleSegmentView() {
        val values = SegmentView.entries
        selectedSegmentView = values[(selectedSegmentView.ordinal + 1) % values.size]
    }

    fun resetAll() {
        fightHistory.clear()
        currentSegment = CombatSegment(WowDpsI18n.tr("wowdps.hud.waiting_title"))
        overallSegment = CombatSegment(WowDpsI18n.tr("wowdps.segment.overall"))
        selectedSegmentView = SegmentView.CURRENT
    }

    fun formatCompactNumber(value: Double): String {
        return when {
            value >= 1_000_000.0 -> String.format("%.2fM", value / 1_000_000.0)
            value >= 10_000.0 -> String.format("%.1fK", value / 1_000.0)
            value >= 1_000.0 -> String.format("%.2fK", value / 1_000.0)
            else -> String.format("%.1f", value)
        }
    }

    fun formatDuration(seconds: Double): String {
        val totalSec = seconds.toInt()
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format("%02d:%02d", mins, secs)
    }
}
