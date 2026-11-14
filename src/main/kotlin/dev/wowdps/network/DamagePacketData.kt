package dev.wowdps.network

import net.minecraft.network.PacketByteBuf
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * Synchronized combat event sent from Server -> Client (`wowdps:combat_event`) v7.0.
 * Strictly separates:
 * 1) The exact SPELL / MAGIC / ABILITY that dealt the damage (`spellId`, `abilityId`, `spellTextureId`, `magicSchool`, `iconItemId`)
 * 2) The SOURCE WEAPON / STAFF / SWORD from which it was cast (`sourceWeaponName`, `sourceWeaponIconId`, `sourceWeaponGlint`)
 * 3) The TARGET MOB / BOSS / DUMMY (`victimName`, `victimEntityTypeId`, `targetIconItemId`)
 * 4) The PARTY / TEAMMATE status (`teamId`, `teamName`, `teamSystem`, `isTeammate`) from FTB Teams / Open Parties and Claims!
 */
data class CombatDamageEvent(
    val timestampMs: Long,
    val attackerUuid: UUID,
    val attackerName: String,
    val victimUuid: UUID,
    val victimName: String,
    val victimTypeName: String,
    val victimEntityTypeId: String,
    val targetIconItemId: String,
    val victimIsBoss: Boolean,
    val spellId: String,            // Raw registry ID of the spell if magical (e.g. "wizards:fireball", "paladins:holy_shock")
    val abilityId: String,          // Clear name of the Spell / Magic / Attack (NOT the staff name!)
    val iconItemId: String,         // Representative rune/spell item icon ID for the spell
    val spellTextureId: String,     // Direct PNG texture path for the spell ("wizards:textures/spell/fireball.png")
    val magicSchool: String,        // "physical", "fire", "frost", "arcane", "lightning", "soul", "holy", "blood", "void", "nature"
    val iconHasGlint: Boolean,
    val sourceWeaponName: String,   // The Staff / Wand / Sword / Bow held when casting or attacking (e.g. "Посох Огня")
    val sourceWeaponIconId: String, // Registry ID of the held Staff / Wand / Sword / Bow
    val sourceWeaponGlint: Boolean, // Enchantment glint on the held weapon
    val damageTypeId: String,
    val rawAmount: Float,
    val effectiveDamage: Float,
    val mitigatedDamage: Float,
    val overkillDamage: Float,
    val isCritical: Boolean,
    val viaPetOrSummon: Boolean,
    val teamId: String = "",        // Party/Team ID from FTB Teams / Open Parties and Claims / Scoreboard
    val teamName: String = "",      // Party/Team display name
    val teamSystem: String = "",    // "FTB", "OPAC", "Тим", or ""
    val isTeammate: Boolean = false, // True if attacker is in the recipient's FTB Team / Open Party
    val isCombatTrigger: Boolean = true // True when damage was dealt by an actual mob/boss or player, NOT environmental!
) {
    fun writeToBuf(buf: PacketByteBuf) {
        buf.writeLong(timestampMs)
        buf.writeUuid(attackerUuid)
        buf.writeString(attackerName, 64)
        buf.writeUuid(victimUuid)
        buf.writeString(victimName, 64)
        buf.writeString(victimTypeName, 64)
        buf.writeString(victimEntityTypeId, 128)
        buf.writeString(targetIconItemId, 128)
        buf.writeBoolean(victimIsBoss)
        buf.writeString(spellId, 128)
        buf.writeString(abilityId, 128)
        buf.writeString(iconItemId, 128)
        buf.writeString(spellTextureId, 128)
        buf.writeString(magicSchool, 32)
        buf.writeBoolean(iconHasGlint)
        buf.writeString(sourceWeaponName, 128)
        buf.writeString(sourceWeaponIconId, 128)
        buf.writeBoolean(sourceWeaponGlint)
        buf.writeString(damageTypeId, 128)
        buf.writeFloat(rawAmount)
        buf.writeFloat(effectiveDamage)
        buf.writeFloat(mitigatedDamage)
        buf.writeFloat(overkillDamage)
        buf.writeBoolean(isCritical)
        buf.writeBoolean(viaPetOrSummon)
        buf.writeString(teamId, 64)
        buf.writeString(teamName, 64)
        buf.writeString(teamSystem, 16)
        buf.writeBoolean(isTeammate)
        buf.writeBoolean(isCombatTrigger)
    }

    companion object {
        val PACKET_ID = Identifier("wowdps", "combat_event")

        fun readFromBuf(buf: PacketByteBuf): CombatDamageEvent {
            return CombatDamageEvent(
                timestampMs = buf.readLong(),
                attackerUuid = buf.readUuid(),
                attackerName = buf.readString(64),
                victimUuid = buf.readUuid(),
                victimName = buf.readString(64),
                victimTypeName = buf.readString(64),
                victimEntityTypeId = buf.readString(128),
                targetIconItemId = buf.readString(128),
                victimIsBoss = buf.readBoolean(),
                spellId = buf.readString(128),
                abilityId = buf.readString(128),
                iconItemId = buf.readString(128),
                spellTextureId = buf.readString(128),
                magicSchool = buf.readString(32),
                iconHasGlint = buf.readBoolean(),
                sourceWeaponName = buf.readString(128),
                sourceWeaponIconId = buf.readString(128),
                sourceWeaponGlint = buf.readBoolean(),
                damageTypeId = buf.readString(128),
                rawAmount = buf.readFloat(),
                effectiveDamage = buf.readFloat(),
                mitigatedDamage = buf.readFloat(),
                overkillDamage = buf.readFloat(),
                isCritical = buf.readBoolean(),
                viaPetOrSummon = buf.readBoolean(),
                teamId = buf.readString(64),
                teamName = buf.readString(64),
                teamSystem = buf.readString(16),
                isTeammate = buf.readBoolean(),
                isCombatTrigger = buf.readBoolean()
            )
        }
    }
}
