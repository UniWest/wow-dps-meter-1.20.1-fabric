package dev.wowdps.server

import dev.wowdps.network.CombatDamageEvent
import dev.wowdps.team.PartyTeamIntegration
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.entity.FlyingItemEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.Ownable
import net.minecraft.entity.boss.WitherEntity
import net.minecraft.entity.boss.dragon.EnderDragonEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.mob.CreeperEntity
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.entity.mob.PiglinEntity
import net.minecraft.entity.mob.SkeletonEntity
import net.minecraft.entity.mob.SpiderEntity
import net.minecraft.entity.mob.WardenEntity
import net.minecraft.entity.mob.WitherSkeletonEntity
import net.minecraft.entity.mob.ZombieEntity
import net.minecraft.entity.passive.IronGolemEntity
import net.minecraft.entity.passive.TameableEntity
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.entity.projectile.TridentEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.SpawnEggItem
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Universal Server-Side Spell, Weapon-to-Spell & Modded Mob Resolver (v6.0 — Prominence II Edition).
 * - Detects the EXACT Spell ID & Spell Name (e.g. "wizards:fireball" -> "Огненный шар (Fireball)"),
 *   never confusing the spell with the staff/wand/sword it was cast from.
 * - Simultaneously records `sourceWeaponName` and `sourceWeaponIconId` so the client can show
 *   a full expandable breakdown of WHICH spells were cast from WHICH staff/sword!
 */
object DamageHookContext {

    private data class DamageSnapshot(
        var rawIncoming: Float,
        val healthBefore: Float,
        val absorptionBefore: Float,
        var postMitigationDamage: Float = -1f,
        var emitted: Boolean = false
    )

    private data class DedupeRecord(
        val worldTime: Long,
        val timestampMs: Long,
        val damageTypeName: String,
        val amount: Float
    )

    private data class PlayerTagRecord(
        val playerUuid: UUID,
        val timestampMs: Long,
        val lastSpellId: String,
        val lastSpellName: String,
        val lastSpellIconId: String,
        val lastSpellTextureId: String,
        val lastMagicSchool: String,
        val lastSourceWeaponName: String,
        val lastSourceWeaponIconId: String,
        val lastSourceWeaponGlint: Boolean
    )

    private val snapshots = ConcurrentHashMap<Int, DamageSnapshot>()
    private val lastEmittedByEntity = ConcurrentHashMap<Int, DedupeRecord>()
    private val playerTaggedMobs = ConcurrentHashMap<Int, PlayerTagRecord>()
    private val recentPlayerSpells = ConcurrentHashMap<UUID, Pair<Identifier, Long>>()

    private val PASSIVE_ENVIRONMENTAL_TYPES = setOf(
        "fall", "in_wall", "cramming", "drown", "starve", "cactus",
        "sweet_berry_bush", "stalagmite", "falling_block", "falling_stalactite",
        "fly_into_wall", "out_of_world", "outside_border", "generic", "dry_out"
    )

    private val KNOWN_SPELL_NAMESPACES = setOf(
        "wizards", "paladins", "archers", "rogues", "spell_engine", "spell_power",
        "simplyswords", "soulsweapons", "more_rpg_classes", "mrpg_lib", "elemental_wizards_rpg",
        "forcemaster_rpg", "witcher_rpg", "berserker_rpg", "bewitchment", "botania", "archon",
        "artifacts", "zenith", "prominent", "bomd", "adventurez"
    )

    fun registerServerHooks() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            if (!entity.world.isClient && amount > 0f) {
                recordRawIncoming(entity, source, amount)
            }
            true
        }
    }

    fun recordRawIncoming(entity: LivingEntity, source: DamageSource, rawAmount: Float) {
        val existing = snapshots[entity.id]
        if (existing == null || existing.emitted) {
            snapshots[entity.id] = DamageSnapshot(
                rawIncoming = rawAmount,
                healthBefore = entity.health,
                absorptionBefore = entity.absorptionAmount
            )
        } else if (rawAmount > existing.rawIncoming) {
            existing.rawIncoming = rawAmount
        }
    }

    fun onPostMitigationCalculated(
        victim: LivingEntity,
        source: DamageSource,
        preMagicAmount: Float,
        finalDamage: Float
    ) {
        if (finalDamage <= 0.001f) return
        val snap = snapshots.getOrPut(victim.id) {
            DamageSnapshot(max(preMagicAmount, finalDamage), victim.health, victim.absorptionAmount)
        }
        snap.postMitigationDamage = finalDamage

        val raw = max(snap.rawIncoming, max(preMagicAmount, finalDamage))
        val mitigated = max(0f, raw - finalDamage)
        snap.emitted = true

        emitCombatEventDeduped(
            victim = victim,
            source = source,
            rawAmount = raw,
            effectiveDamage = finalDamage,
            mitigatedDamage = mitigated,
            overkillDamage = 0f
        )
    }

    fun onDamageTrackerRecorded(victim: LivingEntity, source: DamageSource, damage: Float) {
        if (damage <= 0.001f) return
        val snap = snapshots[victim.id]
        val raw = if (snap != null && snap.rawIncoming > 0f) max(snap.rawIncoming, damage) else damage
        val mitigated = max(0f, raw - damage)
        snap?.emitted = true

        emitCombatEventDeduped(
            victim = victim,
            source = source,
            rawAmount = raw,
            effectiveDamage = damage,
            mitigatedDamage = mitigated,
            overkillDamage = 0f
        )
    }

    fun beginOuterDamage(entityId: Int, rawAmount: Float, healthBefore: Float, absorptionBefore: Float) {
        snapshots[entityId] = DamageSnapshot(rawAmount, healthBefore, absorptionBefore)
    }

    fun beginApplyDamage(entityId: Int, rawAmount: Float, healthBefore: Float, absorptionBefore: Float) {
        val snap = snapshots[entityId]
        if (snap == null) {
            snapshots[entityId] = DamageSnapshot(rawAmount, healthBefore, absorptionBefore)
        } else if (rawAmount > snap.rawIncoming) {
            snap.rawIncoming = rawAmount
        }
    }

    fun completeApplyDamage(
        victim: LivingEntity,
        source: DamageSource,
        appliedAmount: Float,
        healthAfter: Float,
        absorptionAfter: Float
    ) {
        val snap = snapshots.remove(victim.id) ?: return
        val hpLost = max(0f, snap.healthBefore - healthAfter)
        val absLost = max(0f, snap.absorptionBefore - absorptionAfter)

        val effectiveDamage = when {
            hpLost + absLost > 0.001f -> hpLost + absLost
            snap.postMitigationDamage > 0.001f -> snap.postMitigationDamage
            else -> max(0f, appliedAmount)
        }
        if (effectiveDamage <= 0.001f) return

        val raw = max(snap.rawIncoming, effectiveDamage)
        val mitigated = max(0f, raw - effectiveDamage)
        val overkill = if (healthAfter <= 0f && raw > effectiveDamage) {
            max(0f, raw - effectiveDamage)
        } else {
            0f
        }

        emitCombatEventDeduped(
            victim = victim,
            source = source,
            rawAmount = raw,
            effectiveDamage = effectiveDamage,
            mitigatedDamage = mitigated,
            overkillDamage = overkill
        )
    }

    fun completeOuterDamage(
        victim: LivingEntity,
        source: DamageSource,
        rawAmount: Float,
        damageSucceeded: Boolean,
        healthAfter: Float,
        absorptionAfter: Float
    ) {
        val snap = snapshots.remove(victim.id)
        if (snap != null && snap.emitted) return

        val hpBefore = snap?.healthBefore ?: healthAfter
        val absBefore = snap?.absorptionBefore ?: absorptionAfter
        val hpLost = max(0f, hpBefore - healthAfter)
        val absLost = max(0f, absBefore - absorptionAfter)

        val effective = when {
            hpLost + absLost > 0.001f -> hpLost + absLost
            snap != null && snap.postMitigationDamage > 0.001f -> snap.postMitigationDamage
            damageSucceeded && rawAmount > 0.001f -> rawAmount
            else -> 0f
        }
        if (effective <= 0.001f) return

        val raw = max(snap?.rawIncoming ?: rawAmount, effective)
        emitCombatEventDeduped(
            victim = victim,
            source = source,
            rawAmount = raw,
            effectiveDamage = effective,
            mitigatedDamage = max(0f, raw - effective),
            overkillDamage = 0f
        )
    }

    private fun emitCombatEventDeduped(
        victim: LivingEntity,
        source: DamageSource,
        rawAmount: Float,
        effectiveDamage: Float,
        mitigatedDamage: Float,
        overkillDamage: Float
    ) {
        val serverWorld = victim.world as? ServerWorld ?: return
        val nowMs = System.currentTimeMillis()
        val worldTick = serverWorld.time
        val sourceKey = source.name

        val prev = lastEmittedByEntity[victim.id]
        if (prev != null &&
            prev.worldTime == worldTick &&
            (nowMs - prev.timestampMs) < 40L &&
            prev.damageTypeName == sourceKey &&
            abs(prev.amount - effectiveDamage) < max(0.5f, effectiveDamage * 0.2f)
        ) {
            return
        }

        val damageTypeKey = try {
            serverWorld.registryManager
                .get(RegistryKeys.DAMAGE_TYPE)
                .getId(source.type)
                ?.toString() ?: source.name
        } catch (_: Exception) {
            source.name
        }

        val attribution = resolvePlayerAttackerStrict(source, victim, serverWorld, damageTypeKey, nowMs)
        val attackerPlayer = attribution?.playerEntity

        // Strictly ignore damage without player participation!
        if (attackerPlayer == null && victim !is PlayerEntity) {
            return
        }

        lastEmittedByEntity[victim.id] = DedupeRecord(worldTick, nowMs, sourceKey, effectiveDamage)

        val attackerUuid: UUID = attackerPlayer?.uuid ?: UUID(0L, 0L)
        val attackerName: String = attackerPlayer?.name?.string ?: source.name

        val resolved = resolveSpellAndSourceWeapon(
            source = source,
            attacker = attackerPlayer,
            victim = victim,
            serverWorld = serverWorld,
            petOrProjectileName = attribution?.petOrProjectileName,
            viaPetOrSummon = attribution?.viaPetOrSummon == true,
            damageTypeKey = damageTypeKey,
            nowMs = nowMs
        )

        if (attackerPlayer != null && attribution.isDirectHit) {
            playerTaggedMobs[victim.id] = PlayerTagRecord(
                playerUuid = attackerPlayer.uuid,
                timestampMs = nowMs,
                lastSpellId = resolved.spellId,
                lastSpellName = resolved.abilityName,
                lastSpellIconId = resolved.iconItemId,
                lastSpellTextureId = resolved.spellTextureId,
                lastMagicSchool = resolved.magicSchool,
                lastSourceWeaponName = resolved.sourceWeaponName,
                lastSourceWeaponIconId = resolved.sourceWeaponIconId,
                lastSourceWeaponGlint = resolved.sourceWeaponGlint
            )
        }

        val isCrit = detectCriticalHit(attackerPlayer, damageTypeKey)
        val isDummy = isTargetDummy(victim)
        val isBoss = victim is EnderDragonEntity || victim is WitherEntity || victim is WardenEntity || (!isDummy && victim.maxHealth >= 100f)
        val victimTypeName = if (isDummy) "@tr:wowdps.attack.dummy" else victim.type.name.string
        val victimEntityTypeId = Registries.ENTITY_TYPE.getId(victim.type).toString()
        val targetIconId = resolveTargetIconItemId(victim, victimEntityTypeId, isDummy, isBoss)

        val server = serverWorld.server
        val teamInfo = PartyTeamIntegration.resolveServerTeam(server, attackerPlayer)

        // Combat mode trigger check:
        // A combat encounter starts ONLY when damage was inflicted by or to a Player by an actual living Entity (Mob / Boss / Player / Pet / Projectile)!
        // Pure environmental damage (fall, fire/lava without mob, drown, starve, suffocation, cactus) does NOT trigger combat mode.
        val directSource = source.source
        val rawAttacker = source.attacker
        val isCausedByLivingEntity = (rawAttacker is LivingEntity) || (directSource is LivingEntity) ||
            (directSource is ProjectileEntity && directSource.owner is LivingEntity)
        val isCombatTrigger = if (attackerPlayer != null) {
            true // Player or pet attacked a mob / dummy / player
        } else if (victim is PlayerEntity) {
            isCausedByLivingEntity // Player was attacked by a mob / another player!
        } else {
            false
        }

        val baseEvent = CombatDamageEvent(
            timestampMs = nowMs,
            attackerUuid = attackerUuid,
            attackerName = attackerName,
            victimUuid = victim.uuid,
            victimName = victim.name.string,
            victimTypeName = victimTypeName,
            victimEntityTypeId = victimEntityTypeId,
            targetIconItemId = targetIconId,
            victimIsBoss = isBoss,
            spellId = resolved.spellId,
            abilityId = resolved.abilityName,
            iconItemId = resolved.iconItemId,
            spellTextureId = resolved.spellTextureId,
            magicSchool = resolved.magicSchool,
            iconHasGlint = resolved.hasGlint,
            sourceWeaponName = resolved.sourceWeaponName,
            sourceWeaponIconId = resolved.sourceWeaponIconId,
            sourceWeaponGlint = resolved.sourceWeaponGlint,
            damageTypeId = damageTypeKey,
            rawAmount = rawAmount,
            effectiveDamage = effectiveDamage,
            mitigatedDamage = mitigatedDamage,
            overkillDamage = overkillDamage,
            isCritical = isCrit,
            viaPetOrSummon = attribution?.viaPetOrSummon == true,
            teamId = teamInfo.teamId,
            teamName = teamInfo.teamName,
            teamSystem = teamInfo.teamSystem,
            isTeammate = false,
            isCombatTrigger = isCombatTrigger
        )

        val nearbyPlayers = serverWorld.players.filter { it.squaredDistanceTo(victim) <= 64.0 * 64.0 }
        val recipients = linkedSetOf<ServerPlayerEntity>()
        recipients.addAll(PlayerLookup.tracking(victim))
        recipients.addAll(nearbyPlayers)
        if (attackerPlayer is ServerPlayerEntity) {
            recipients.add(attackerPlayer)
            // Send to all online teammates in FTB Teams / Open Parties and Claims anywhere on the server!
            recipients.addAll(PartyTeamIntegration.getOnlineServerTeammates(server, attackerPlayer))
        }
        if (victim is ServerPlayerEntity) {
            recipients.add(victim)
            recipients.addAll(PartyTeamIntegration.getOnlineServerTeammates(server, victim))
        }

        for (recipient in recipients) {
            val isAlly = attackerUuid != UUID(0L, 0L) &&
                PartyTeamIntegration.areTeammatesOnServer(server, attackerUuid, recipient.uuid)
            val pktEvent = baseEvent.copy(isTeammate = isAlly)
            val buf = PacketByteBufs.create()
            pktEvent.writeToBuf(buf)
            ServerPlayNetworking.send(recipient, CombatDamageEvent.PACKET_ID, buf)
        }
    }

    private data class PlayerAttackerResolution(
        val playerEntity: PlayerEntity,
        val viaPetOrSummon: Boolean,
        val petOrProjectileName: String?,
        val isDirectHit: Boolean
    )

    private fun resolvePlayerAttackerStrict(
        source: DamageSource,
        victim: LivingEntity,
        serverWorld: ServerWorld,
        damageTypeKey: String,
        nowMs: Long
    ): PlayerAttackerResolution? {
        val direct = source.source
        val attacker = source.attacker

        if (attacker is PlayerEntity) {
            val projName = if (direct is ProjectileEntity && direct != attacker) direct.name.string else null
            return PlayerAttackerResolution(attacker, false, projName, true)
        }
        if (direct is PlayerEntity) {
            return PlayerAttackerResolution(direct, false, null, true)
        }

        val petOwner = findPlayerOwner(attacker)
        if (petOwner != null && attacker != null) {
            return PlayerAttackerResolution(petOwner, true, attacker.name.string, true)
        }

        if (direct is ProjectileEntity) {
            val projOwner = direct.owner
            if (projOwner is PlayerEntity) {
                return PlayerAttackerResolution(projOwner, false, direct.name.string, true)
            }
            val projPetOwner = findPlayerOwner(projOwner)
            if (projPetOwner != null) {
                return PlayerAttackerResolution(projPetOwner, true, projOwner?.name?.string ?: direct.name.string, true)
            }
        }

        if (attacker != null) {
            return null
        }

        val cleanType = damageTypeKey.substringAfter(":")
        if (cleanType in PASSIVE_ENVIRONMENTAL_TYPES) {
            return null
        }

        val tag = playerTaggedMobs[victim.id]
        if (tag != null && (nowMs - tag.timestampMs) <= 8000L) {
            val taggedPlayer = serverWorld.getPlayerByUuid(tag.playerUuid) as? PlayerEntity
            if (taggedPlayer != null) {
                return PlayerAttackerResolution(taggedPlayer, false, null, false)
            }
        }

        val prime = victim.primeAdversary
        if (prime is PlayerEntity && (victim.age - victim.lastAttackedTime) <= 100) {
            return PlayerAttackerResolution(prime, false, null, false)
        }

        return null
    }

    private fun findPlayerOwner(entity: Entity?): PlayerEntity? {
        var current: Entity? = entity
        var depth = 0
        while (current != null && depth < 4) {
            if (current is PlayerEntity) return current
            current = when (current) {
                is TameableEntity -> current.owner
                is Ownable -> current.owner
                is ProjectileEntity -> current.owner
                else -> null
            }
            depth++
        }
        return null
    }

    private data class SpellAndWeaponResult(
        val spellId: String,
        val abilityName: String,
        val iconItemId: String,
        val spellTextureId: String,
        val magicSchool: String,
        val hasGlint: Boolean,
        val sourceWeaponName: String,
        val sourceWeaponIconId: String,
        val sourceWeaponGlint: Boolean
    )

    /**
     * Core v6.0 Resolver:
     * 1) Determines the SOURCE WEAPON (`sourceWeaponName`, `sourceWeaponIconId`) — e.g. "Посох Огня", "Тайный жезл", "Незеритовый меч".
     * 2) Determines the EXACT SPELL / MAGIC (`spellId`, `abilityName`, `spellTextureId`) — NEVER using the staff's name as the spell name!
     */
    private fun resolveSpellAndSourceWeapon(
        source: DamageSource,
        attacker: PlayerEntity?,
        victim: LivingEntity,
        serverWorld: ServerWorld,
        petOrProjectileName: String?,
        viaPetOrSummon: Boolean,
        damageTypeKey: String,
        nowMs: Long
    ): SpellAndWeaponResult {
        val direct = source.source
        val mainHand: ItemStack = attacker?.mainHandStack ?: ItemStack.EMPTY
        val offHand: ItemStack = attacker?.offHandStack ?: ItemStack.EMPTY

        // Identify the source weapon/staff/item held by the player
        val sourceWeaponStack = when {
            !mainHand.isEmpty -> mainHand
            !offHand.isEmpty -> offHand
            else -> ItemStack.EMPTY
        }
        val sourceWeaponName = when {
            viaPetOrSummon && petOrProjectileName != null -> "@tr:wowdps.attack.pet|$petOrProjectileName"
            !sourceWeaponStack.isEmpty -> sourceWeaponStack.name.string
            else -> "@tr:wowdps.wpn.bare_hands"
        }
        val sourceWeaponIconId = when {
            viaPetOrSummon -> "minecraft:bone"
            !sourceWeaponStack.isEmpty -> Registries.ITEM.getId(sourceWeaponStack.item).toString()
            else -> "minecraft:enchanted_book"
        }
        val sourceWeaponGlint = !sourceWeaponStack.isEmpty && sourceWeaponStack.hasGlint()

        val detectedSchool = detectMagicSchool(damageTypeKey, direct, mainHand)
        val cleanType = damageTypeKey.substringAfter(":")
        val isMeleeDamageType = cleanType == "player_attack" || cleanType == "mob_attack" || cleanType == "sting" || cleanType == "mob_attack_no_aggro"

        // 1. Pet / Summon attack
        if (viaPetOrSummon && petOrProjectileName != null) {
            return SpellAndWeaponResult(
                spellId = "",
                abilityName = "@tr:wowdps.attack.pet|$petOrProjectileName",
                iconItemId = "minecraft:bone",
                spellTextureId = "",
                magicSchool = detectedSchool,
                hasGlint = false,
                sourceWeaponName = sourceWeaponName,
                sourceWeaponIconId = sourceWeaponIconId,
                sourceWeaponGlint = false
            )
        }

        // 2. Deep Spell Engine & Modded Spell Detection (Projectiles, Clouds, Cooldowns, Cast Process, Equipped SpellContainer)
        val exactSpellId = if (attacker != null) {
            detectExactSpellId(attacker, victim, direct, serverWorld, detectedSchool, isMeleeDamageType, nowMs)
        } else {
            null
        }

        if (exactSpellId != null) {
            val spellStr = exactSpellId.toString()
            val spellTexture = "${exactSpellId.namespace}:textures/spell/${exactSpellId.path}.png"
            val schoolFromSpell = if (detectedSchool != "physical") detectedSchool else guessSchoolFromSpellId(exactSpellId)
            val spellDisplayName = getClearSpellName(exactSpellId, schoolFromSpell)
            val spellRuneIcon = resolvePureMagicIconItem(schoolFromSpell, exactSpellId)

            return SpellAndWeaponResult(
                spellId = spellStr,
                abilityName = spellDisplayName,
                iconItemId = spellRuneIcon,
                spellTextureId = spellTexture,
                magicSchool = schoolFromSpell,
                hasGlint = true,
                sourceWeaponName = sourceWeaponName,
                sourceWeaponIconId = sourceWeaponIconId,
                sourceWeaponGlint = sourceWeaponGlint
            )
        }

        // 3. Non-SpellEngine Projectile (Bow arrow, Crossbow arrow/firework, Trident, Thrown Potion)
        if (direct is ProjectileEntity) {
            if (direct is FlyingItemEntity) {
                val projStack = direct.stack
                if (!projStack.isEmpty) {
                    val itemId = Registries.ITEM.getId(projStack.item).toString()
                    return SpellAndWeaponResult(
                        spellId = "",
                        abilityName = projStack.name.string,
                        iconItemId = itemId,
                        spellTextureId = "",
                        magicSchool = detectedSchool,
                        hasGlint = projStack.hasGlint(),
                        sourceWeaponName = sourceWeaponName,
                        sourceWeaponIconId = sourceWeaponIconId,
                        sourceWeaponGlint = sourceWeaponGlint
                    )
                }
            }
            if (direct is TridentEntity) {
                return SpellAndWeaponResult(
                    spellId = "",
                    abilityName = "@tr:wowdps.attack.trident",
                    iconItemId = "minecraft:trident",
                    spellTextureId = "",
                    magicSchool = "physical",
                    hasGlint = true,
                    sourceWeaponName = sourceWeaponName,
                    sourceWeaponIconId = "minecraft:trident",
                    sourceWeaponGlint = true
                )
            }
            // If projectile dealt magical damage (e.g. magic arrow / wand bolt without explicit spellId)
            if (detectedSchool != "physical") {
                val schoolSpellName = getClearSchoolSpellName(detectedSchool, damageTypeKey, true)
                val magicIcon = resolvePureMagicIconItem(detectedSchool, null)
                return SpellAndWeaponResult(
                    spellId = "spell_power:$detectedSchool",
                    abilityName = schoolSpellName,
                    iconItemId = magicIcon,
                    spellTextureId = "spell_power:textures/mob_effect/$detectedSchool.png",
                    magicSchool = detectedSchool,
                    hasGlint = true,
                    sourceWeaponName = sourceWeaponName,
                    sourceWeaponIconId = sourceWeaponIconId,
                    sourceWeaponGlint = sourceWeaponGlint
                )
            }

            val projName = petOrProjectileName ?: direct.name.string
            return SpellAndWeaponResult(
                spellId = "",
                abilityName = "@tr:wowdps.attack.shot|$projName",
                iconItemId = "minecraft:arrow",
                spellTextureId = "",
                magicSchool = "physical",
                hasGlint = false,
                sourceWeaponName = sourceWeaponName,
                sourceWeaponIconId = sourceWeaponIconId,
                sourceWeaponGlint = sourceWeaponGlint
            )
        }

        // 4. Pure Melee Physical Strike (Sword, Axe, Staff melee hit, Fist)
        if (isMeleeDamageType && detectedSchool == "physical") {
            val meleeLabel = if (!sourceWeaponStack.isEmpty) {
                "@tr:wowdps.attack.melee|${sourceWeaponStack.name.string}"
            } else {
                "@tr:wowdps.attack.hand"
            }
            return SpellAndWeaponResult(
                spellId = "",
                abilityName = meleeLabel,
                iconItemId = sourceWeaponIconId,
                spellTextureId = "",
                magicSchool = "physical",
                hasGlint = sourceWeaponGlint,
                sourceWeaponName = sourceWeaponName,
                sourceWeaponIconId = sourceWeaponIconId,
                sourceWeaponGlint = sourceWeaponGlint
            )
        }

        // 5. Magic / Elemental / DoT / Status Effect Damage (WITHOUT putting the staff name as the spell!)
        // Check if victim has a damaging modded status effect active (e.g. bleed, burn, poison, frostbite)
        val activeDebuffSpell = detectVictimDebuffSpell(victim, detectedSchool)
        val effectiveSchool = if (detectedSchool != "physical") detectedSchool else "arcane"
        val clearMagicName = activeDebuffSpell ?: getClearSchoolSpellName(effectiveSchool, damageTypeKey, false)
        val pureMagicIcon = resolvePureMagicIconItem(effectiveSchool, null)

        return SpellAndWeaponResult(
            spellId = "spell_power:$effectiveSchool",
            abilityName = clearMagicName,
            iconItemId = pureMagicIcon,
            spellTextureId = "spell_power:textures/mob_effect/$effectiveSchool.png",
            magicSchool = effectiveSchool,
            hasGlint = true,
            sourceWeaponName = sourceWeaponName,
            sourceWeaponIconId = sourceWeaponIconId,
            sourceWeaponGlint = sourceWeaponGlint
        )
    }

    /**
     * Multi-stage Deep Spell Engine Detector for Prominence II: Hasturian Era:
     * Stage 1: Check `directSource` and nearby `SpellProjectile` / `SpellCloud` entities within 6 blocks via NBT (`spell_id`) and fields.
     * Stage 2: Check `player` active cast/channel process (`getCurrentSpellId`, `getSpellCastProcess`, Mixin fields).
     * Stage 3: Check `player` `SpellCooldownManager` for the spell put on cooldown on this exact tick / within last 2 seconds!
     * Stage 4: Read `SpellContainerHelper.getEquipped(mainHand, player)` + item NBT `spell_container` (`spell_ids`) and match the spell's school!
     */
    private fun detectExactSpellId(
        player: PlayerEntity,
        victim: LivingEntity,
        directSource: Entity?,
        serverWorld: ServerWorld,
        detectedSchool: String,
        isMeleeDamageType: Boolean,
        nowMs: Long
    ): Identifier? {
        // Stage 1A: Direct source entity NBT & reflection
        if (directSource != null && directSource != player) {
            extractSpellIdFromEntity(directSource)?.let {
                recentPlayerSpells[player.uuid] = it to nowMs
                return it
            }
        }

        // Stage 1B: Nearby SpellProjectile / SpellAreaEffect / Cloud entities within 6 blocks of victim
        try {
            val nearbyEntities = serverWorld.getOtherEntities(victim, victim.boundingBox.expand(6.0))
            for (ent in nearbyEntities) {
                if (ent is PlayerEntity || ent is LivingEntity) continue
                val clsName = ent.javaClass.name.lowercase()
                val typeNamespace = Registries.ENTITY_TYPE.getId(ent.type).namespace
                if (clsName.contains("spell") || typeNamespace in KNOWN_SPELL_NAMESPACES) {
                    extractSpellIdFromEntity(ent)?.let {
                        recentPlayerSpells[player.uuid] = it to nowMs
                        return it
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Stage 2: Active Cast / Channeling on Player (`SpellCasterEntity`)
        extractActiveCastSpellFromPlayer(player)?.let {
            recentPlayerSpells[player.uuid] = it to nowMs
            return it
        }

        // Stage 3: Player's `SpellCooldownManager` (most recently triggered cooldown!)
        extractMostRecentCooldownSpell(player, detectedSchool)?.let {
            recentPlayerSpells[player.uuid] = it to nowMs
            return it
        }

        // Stage 4: If this is magical/spell damage (or non-basic melee), check recent cast cache (within 2.5s)
        if (!isMeleeDamageType || detectedSchool != "physical") {
            val cached = recentPlayerSpells[player.uuid]
            if (cached != null && (nowMs - cached.second) <= 2500L) {
                val cachedSchool = guessSchoolFromSpellId(cached.first)
                if (detectedSchool == "physical" || cachedSchool == detectedSchool || cachedSchool == "arcane") {
                    return cached.first
                }
            }

            // Stage 5: Inspect the equipped SpellContainer on the player's held Staff/Wand/Sword + Trinket SpellBook!
            extractMatchingSpellFromEquippedContainer(player, detectedSchool)?.let {
                return it
            }
        }

        return null
    }

    /**
     * Extracts `spell_id` from a `SpellProjectile` or `SpellCloud` entity via NBT and reflection.
     */
    private fun extractSpellIdFromEntity(entity: Entity): Identifier? {
        // 1. Check entity NBT ("spell_id", "SpellId", "spell")
        try {
            val nbt = NbtCompound()
            entity.writeNbt(nbt)
            for (key in listOf("spell_id", "SpellId", "spell", "Spell")) {
                if (nbt.contains(key, NbtElement.STRING_TYPE.toInt())) {
                    val str = nbt.getString(key)
                    val id = Identifier.tryParse(str)
                    if (id != null && id.namespace != "minecraft") return id
                }
            }
            // Scan all string tags in NBT for a spell identifier
            for (key in nbt.keys) {
                if (nbt.getType(key) == NbtElement.STRING_TYPE) {
                    val v = nbt.getString(key)
                    if (v.contains(":")) {
                        val id = Identifier.tryParse(v)
                        if (id != null && id.namespace in KNOWN_SPELL_NAMESPACES && !v.endsWith("_spawn_egg")) {
                            return id
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Check methods & declared fields on the projectile/cloud class
        try {
            for (m in entity.javaClass.methods) {
                if (m.parameterCount == 0 && (m.name.contains("SpellId", ignoreCase = true) || m.name == "getSpell")) {
                    val res = m.invoke(entity)
                    if (res is Identifier) return res
                }
            }
            var cls: Class<*>? = entity.javaClass
            while (cls != null && cls != Entity::class.java) {
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(entity)
                    if (v is Identifier && v.namespace != "minecraft") return v
                    if (v is String && v.contains(":")) {
                        val id = Identifier.tryParse(v)
                        if (id != null && id.namespace in KNOWN_SPELL_NAMESPACES) return id
                    }
                }
                cls = cls.superclass
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun extractActiveCastSpellFromPlayer(player: PlayerEntity): Identifier? {
        try {
            for (methodName in listOf("getCurrentSpellId", "getSpellCastProcess", "getCurrentSpell")) {
                val m = player.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                if (m != null) {
                    val res = m.invoke(player)
                    if (res is Identifier) return res
                    if (res != null) {
                        for (subM in res.javaClass.methods) {
                            if (subM.parameterCount == 0 && (subM.name == "id" || subM.name == "getId" || subM.name == "spellId")) {
                                val subRes = subM.invoke(res)
                                if (subRes is Identifier) return subRes
                            }
                        }
                        for (f in res.javaClass.declaredFields) {
                            f.isAccessible = true
                            val fv = f.get(res)
                            if (fv is Identifier) return fv
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Inspects the player's `SpellCooldownManager` (injected by Spell Engine) to find the spell
     * that was most recently put on cooldown!
     */
    private fun extractMostRecentCooldownSpell(player: PlayerEntity, detectedSchool: String): Identifier? {
        try {
            val cdMethod = player.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name.contains("CooldownManager", ignoreCase = true) &&
                    it.returnType.name.contains("spell_engine", ignoreCase = true)
            }
            val cdManager = cdMethod?.invoke(player) ?: run {
                var found: Any? = null
                var cls: Class<*>? = player.javaClass
                while (cls != null && cls != Object::class.java) {
                    for (f in cls.declaredFields) {
                        if (f.type.name.contains("SpellCooldownManager", ignoreCase = true)) {
                            f.isAccessible = true
                            found = f.get(player)
                            break
                        }
                    }
                    if (found != null) break
                    cls = cls.superclass
                }
                found
            }

            if (cdManager != null) {
                for (f in cdManager.javaClass.declaredFields) {
                    f.isAccessible = true
                    val mapObj = f.get(cdManager)
                    if (mapObj is Map<*, *>) {
                        var bestId: Identifier? = null
                        for ((k, _) in mapObj) {
                            val id = when (k) {
                                is Identifier -> k
                                is String -> Identifier.tryParse(k)
                                else -> null
                            }
                            if (id != null) {
                                if (detectedSchool == "physical" || guessSchoolFromSpellId(id) == detectedSchool) {
                                    bestId = id
                                } else if (bestId == null) {
                                    bestId = id
                                }
                            }
                        }
                        if (bestId != null) return bestId
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Reads `SpellContainerHelper.getEquipped(mainHandStack, player)` and item NBT `spell_container`
     * to find all spells bound to the player's weapon & equipped Spell Book, matching the damage school!
     */
    private fun extractMatchingSpellFromEquippedContainer(player: PlayerEntity, detectedSchool: String): Identifier? {
        val candidateSpellIds = mutableListOf<Identifier>()

        // 1. Try calling `net.spell_engine.internals.SpellContainerHelper.getEquipped(ItemStack, PlayerEntity)` via reflection
        try {
            val helperCls = Class.forName("net.spell_engine.internals.SpellContainerHelper")
            val getEquippedMethod = helperCls.methods.firstOrNull { it.name == "getEquipped" && it.parameterCount == 2 }
            val containerObj = getEquippedMethod?.invoke(null, player.mainHandStack, player)
            if (containerObj != null) {
                val spellIdsField = containerObj.javaClass.fields.firstOrNull { it.name == "spell_ids" }
                    ?: containerObj.javaClass.declaredFields.firstOrNull { it.name == "spell_ids" }
                spellIdsField?.isAccessible = true
                val listObj = spellIdsField?.get(containerObj) as? Iterable<*>
                if (listObj != null) {
                    for (item in listObj) {
                        val id = Identifier.tryParse(item.toString())
                        if (id != null) candidateSpellIds.add(id)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Also inspect NBT `spell_container` on held items
        for (stack in listOf(player.mainHandStack, player.offHandStack)) {
            if (stack.isEmpty) continue
            val nbt = stack.nbt ?: continue
            if (nbt.contains("spell_container", NbtElement.COMPOUND_TYPE.toInt())) {
                val sc = nbt.getCompound("spell_container")
                val list = sc.getList("spell_ids", NbtElement.STRING_TYPE.toInt())
                for (i in 0 until list.size) {
                    val id = Identifier.tryParse(list.getString(i))
                    if (id != null) candidateSpellIds.add(id)
                }
            }
        }

        if (candidateSpellIds.isEmpty()) return null

        // Match by school in SpellRegistry if possible
        try {
            val regCls = Class.forName("net.spell_engine.internals.SpellRegistry")
            val getSpellMethod = regCls.methods.firstOrNull { it.name == "getSpell" && it.parameterCount == 1 }
            if (getSpellMethod != null) {
                for (id in candidateSpellIds) {
                    val spellObj = getSpellMethod.invoke(null, id) ?: continue
                    val schoolField = spellObj.javaClass.fields.firstOrNull { it.name == "school" }
                    val schoolObj = schoolField?.get(spellObj)?.toString()?.lowercase() ?: ""
                    if (detectedSchool != "physical" && schoolObj.contains(detectedSchool)) {
                        return id
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Match by spell ID name heuristics
        for (id in candidateSpellIds) {
            if (guessSchoolFromSpellId(id) == detectedSchool) {
                return id
            }
        }
        return candidateSpellIds.firstOrNull()
    }

    private fun detectVictimDebuffSpell(victim: LivingEntity, detectedSchool: String): String? {
        for ((effect, _) in victim.activeStatusEffects) {
            val id = Registries.STATUS_EFFECT.getId(effect) ?: continue
            if (!effect.isBeneficial && id.namespace != "minecraft") {
                return "${effect.name.string} (${formatMagicSchoolShort(detectedSchool)})"
            }
        }
        return null
    }

    /**
     * Built-in dictionary of Prominence II / Wizards / Paladins / Archers / Simply Swords spells
     * so every spell has a clear, recognizable name even before client-side I18n translation!
     */
    private fun getClearSpellName(spellId: Identifier, school: String): String {
        val path = spellId.path.lowercase()
        val knownKeys = setOf(
            "fireball", "fire_breath", "meteor", "pyroblast", "scorch", "fire_nova", "wall_of_flames", "fire_wall",
            "frostbolt", "frost_nova", "blizzard", "ice_lance", "frost_shield", "cold_snap",
            "arcane_bolt", "arcane_missile", "arcane_missiles", "arcane_beam", "arcane_blast", "blink",
            "holy_shock", "judgement", "divine_storm", "consecration", "smite", "holy_fire", "penance",
            "power_shot", "barrage", "magic_arrow", "entangling_roots", "whirlwind", "shadowstep",
            "ember_ire", "ember_slash", "frostfall", "frost_slash", "arcane_surge", "storm_call", "lightning_strike",
            "soul_rend", "soul_harvest"
        )
        if (path in knownKeys) {
            return "@tr:wowdps.spell.$path"
        }

        val prettyEn = spellId.path.split('_', '/').joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
        return "$prettyEn [${formatMagicSchoolShort(school)}]"
    }

    private fun getClearSchoolSpellName(school: String, damageTypeKey: String, isProjectile: Boolean): String {
        val cleanPath = damageTypeKey.substringAfter(":")
        return when (school) {
            "fire" -> if (isProjectile) "@tr:wowdps.school.spell_fire_proj" else if (cleanPath.contains("fire") && !damageTypeKey.startsWith("spell_power")) "@tr:wowdps.school.spell_fire_burn" else "@tr:wowdps.school.spell_fire_spell"
            "frost" -> if (isProjectile) "@tr:wowdps.school.spell_frost_proj" else "@tr:wowdps.school.spell_frost_spell"
            "arcane" -> if (isProjectile) "@tr:wowdps.school.spell_arcane_proj" else "@tr:wowdps.school.spell_arcane_spell"
            "lightning" -> "@tr:wowdps.school.spell_lightning"
            "soul" -> "@tr:wowdps.school.spell_soul"
            "holy" -> "@tr:wowdps.school.spell_holy"
            "blood" -> "@tr:wowdps.school.spell_blood"
            "void" -> "@tr:wowdps.school.spell_void"
            "nature" -> "@tr:wowdps.school.spell_nature"
            else -> "@tr:wowdps.school.spell_generic"
        }
    }

    private fun formatMagicSchoolShort(school: String): String = when (school) {
        "fire" -> "Fire"
        "frost" -> "Frost"
        "arcane" -> "Arcane"
        "lightning" -> "Lightning"
        "soul" -> "Soul"
        "holy" -> "Holy"
        "blood" -> "Blood"
        "void" -> "Void"
        "nature" -> "Nature"
        else -> "Magic"
    }

    private fun resolvePureMagicIconItem(school: String, spellId: Identifier?): String {
        val candidates = when (school) {
            "fire" -> listOf("runes:fire_stone", "minecraft:fire_charge", "minecraft:blaze_powder")
            "frost" -> listOf("runes:frost_stone", "minecraft:heart_of_the_sea", "minecraft:blue_ice")
            "arcane" -> listOf("runes:arcane_stone", "minecraft:amethyst_shard", "minecraft:ender_eye")
            "lightning" -> listOf("runes:lightning_stone", "minecraft:prismarine_crystals", "minecraft:lightning_rod")
            "soul" -> listOf("runes:soul_stone", "minecraft:echo_shard", "minecraft:soul_lantern")
            "holy" -> listOf("runes:healing_stone", "minecraft:nether_star", "minecraft:golden_apple")
            "blood" -> listOf("minecraft:redstone", "minecraft:nether_wart")
            "void" -> listOf("minecraft:ender_eye", "minecraft:dragon_breath", "minecraft:echo_shard")
            "nature" -> listOf("minecraft:slime_ball", "minecraft:spore_blossom")
            else -> listOf("minecraft:enchanted_book")
        }
        for (cand in candidates) {
            val id = Identifier.tryParse(cand)
            if (id != null && Registries.ITEM.containsId(id)) {
                return id.toString()
            }
        }
        return "minecraft:enchanted_book"
    }

    private fun detectMagicSchool(damageTypeKey: String, directSource: Entity?, mainHand: ItemStack): String {
        val key = damageTypeKey.lowercase()
        return when {
            key.contains("fire") || key.contains("lava") || key.contains("flame") || key.contains("ember") || key.contains("blaze") -> "fire"
            key.contains("frost") || key.contains("freeze") || key.contains("ice") || key.contains("cold") -> "frost"
            key.contains("arcane") || key.contains("amethyst") -> "arcane"
            key.contains("lightning") || key.contains("electric") || key.contains("thunder") || key.contains("shock") -> "lightning"
            key.contains("soul") || key.contains("wither") || key.contains("lich") -> "soul"
            key.contains("heal") || key.contains("holy") || key.contains("sacred") || key.contains("radiant") -> "holy"
            key.contains("blood") || key.contains("bleed") || key.contains("hemorrhage") -> "blood"
            key.contains("void") || key.contains("shadow") || key.contains("dark") || key.contains("abyss") || key.contains("sonic") -> "void"
            key.contains("nature") || key.contains("earth") || key.contains("poison") || key.contains("thorns") || key.contains("air") || key.contains("wind") -> "nature"
            key.contains("magic") || key.contains("spell") || key.startsWith("spell_power:") || key.startsWith("wizards:") || key.startsWith("paladins:") -> "arcane"
            else -> "physical"
        }
    }

    private fun guessSchoolFromSpellId(spellId: Identifier): String {
        val s = spellId.toString().lowercase()
        return when {
            s.contains("fire") || s.contains("flame") || s.contains("meteor") || s.contains("pyro") || s.contains("ember") || s.contains("scorch") -> "fire"
            s.contains("frost") || s.contains("ice") || s.contains("blizzard") || s.contains("cold") || s.contains("freeze") -> "frost"
            s.contains("arcane") || s.contains("missile") || s.contains("beam") || s.contains("blink") -> "arcane"
            s.contains("lightning") || s.contains("thunder") || s.contains("shock") || s.contains("spark") || s.contains("storm") -> "lightning"
            s.contains("soul") || s.contains("necrotic") || s.contains("wither") -> "soul"
            s.contains("holy") || s.contains("heal") || s.contains("divine") || s.contains("judgement") || s.contains("paladin") || s.contains("smite") -> "holy"
            s.contains("blood") || s.contains("vampir") || s.contains("bleed") -> "blood"
            s.contains("void") || s.contains("shadow") || s.contains("abyss") -> "void"
            else -> "arcane"
        }
    }

    private fun isTargetDummy(entity: LivingEntity): Boolean {
        val typeId = Registries.ENTITY_TYPE.getId(entity.type).toString().lowercase()
        val className = entity.javaClass.name.lowercase()
        val displayName = entity.name.string.lowercase()
        return typeId.contains("dummy") ||
            typeId.contains("dummmmmmy") ||
            className.contains("dummy") ||
            displayName.contains("манекен") ||
            displayName.contains("dummy")
    }

    private fun resolveTargetIconItemId(
        victim: LivingEntity,
        victimEntityTypeId: String,
        isDummy: Boolean,
        isBoss: Boolean
    ): String {
        if (isDummy) return "minecraft:armor_stand"
        if (victim is EnderDragonEntity) return "minecraft:dragon_head"
        if (victim is WitherEntity) return "minecraft:nether_star"
        if (victim is WardenEntity) return "minecraft:sculk_shrieker"
        if (victim is ZombieEntity) return "minecraft:zombie_head"
        if (victim is SkeletonEntity) return "minecraft:skeleton_skull"
        if (victim is WitherSkeletonEntity) return "minecraft:wither_skeleton_skull"
        if (victim is CreeperEntity) return "minecraft:creeper_head"
        if (victim is PiglinEntity) return "minecraft:piglin_head"
        if (victim is SpiderEntity) return "minecraft:spider_eye"
        if (victim is EndermanEntity) return "minecraft:ender_pearl"
        if (victim is VillagerEntity) return "minecraft:emerald"
        if (victim is IronGolemEntity) return "minecraft:iron_block"
        if (victim is PlayerEntity) return "minecraft:player_head"

        val spawnEgg = SpawnEggItem.forEntity(victim.type)
        if (spawnEgg != null) {
            return Registries.ITEM.getId(spawnEgg).toString()
        }

        val namespace = victimEntityTypeId.substringBefore(":")
        val path = victimEntityTypeId.substringAfter(":")
        val candidateItemIds = listOf(
            "$namespace:${path}_spawn_egg",
            "$namespace:spawn_egg_$path",
            "$namespace:${path}_head",
            "$namespace:${path}_skull",
            "$namespace:${path}_trophy",
            "$namespace:$path"
        )
        for (cand in candidateItemIds) {
            val id = Identifier.tryParse(cand)
            if (id != null && Registries.ITEM.containsId(id)) {
                return id.toString()
            }
        }

        return if (isBoss) "minecraft:nether_star" else "minecraft:skeleton_skull"
    }

    private fun detectCriticalHit(attacker: Entity?, damageTypeKey: String): Boolean {
        if (damageTypeKey.contains("crit", ignoreCase = true)) return true
        val player = attacker as? PlayerEntity ?: return false
        return player.fallDistance > 0.0f &&
            !player.isOnGround &&
            !player.isClimbing &&
            !player.isTouchingWater &&
            !player.hasStatusEffect(StatusEffects.BLINDNESS) &&
            !player.hasVehicle()
    }
}
