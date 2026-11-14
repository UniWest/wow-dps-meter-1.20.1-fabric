package dev.wowdps.client

import dev.wowdps.team.PartyTeamIntegration
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Compact, Resizable & Non-Overlapping WoW Details! Inspector Screen (v6.0 — Prominence II Edition).
 * Features:
 * - Tab `🪄 Развёртка`: Expandable tree of every Staff / Sword / Wand and all Spells released from it!
 * - Tab `🔮 Заклинания`: Flat ranking of all exact Spells / Magic abilities with their spell icons and which staff/sword cast them.
 * - Tab `📈 Графики`: 4 switchable graph types.
 * - Tab `🎯 Цели`: All damaged mobs/bosses/dummies with 3D modded mob portraits.
 */
class WowDpsInspectorScreen : Screen(Text.literal("WoW Details! DPS & Spell Meter")) {

    private var selectedCombatantUuid: UUID? = null

    private var draggingHud: Boolean = false
    private var resizingHud: Boolean = false
    private var hudDragOffsetX: Int = 0
    private var hudDragOffsetY: Int = 0
    private var lastRenderedHudHeight: Int = 90

    private var draggingInspector: Boolean = false
    private var resizingInspector: Boolean = false
    private var inspDragOffsetX: Int = 0
    private var inspDragOffsetY: Int = 0

    // Tracks clickable weapon header bounding boxes in WEAPON_TREE tab so user can click to expand/collapse a staff/sword
    private data class WeaponHeaderClickZone(val weaponName: String, val y1: Int, val y2: Int)
    private val weaponClickZones = mutableListOf<WeaponHeaderClickZone>()

    private val T_SCALE = 0.80f

    override fun init() {
        super.init()
        val centerX = width / 2
        val hideKey = WowDpsClient.getToggleHudKeyLabel()

        val y1 = 4
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.mode", CombatTrackerEngine.currentMode.shortName))) { btn ->
                CombatTrackerEngine.currentMode = CombatTrackerEngine.currentMode.next()
                btn.message = Text.literal(WowDpsI18n.tr("wowdps.btn.mode", CombatTrackerEngine.currentMode.shortName))
            }.dimensions(centerX - 214, y1, 64, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.group", CombatTrackerEngine.groupFilterMode.shortName))) { btn ->
                CombatTrackerEngine.groupFilterMode = CombatTrackerEngine.groupFilterMode.next()
                btn.message = Text.literal(WowDpsI18n.tr("wowdps.btn.group", CombatTrackerEngine.groupFilterMode.shortName))
            }.dimensions(centerX - 148, y1, 62, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.fight", CombatTrackerEngine.selectedSegmentView.label(CombatTrackerEngine)))) { btn ->
                CombatTrackerEngine.cycleSegmentView()
                btn.message = Text.literal(WowDpsI18n.tr("wowdps.btn.fight", CombatTrackerEngine.selectedSegmentView.label(CombatTrackerEngine)))
            }.dimensions(centerX - 84, y1, 68, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(if (CombatTrackerEngine.hudVisible) WowDpsI18n.tr("wowdps.btn.hud_shown", hideKey) else WowDpsI18n.tr("wowdps.btn.hud_hidden", hideKey))) { btn ->
                CombatTrackerEngine.hudVisible = !CombatTrackerEngine.hudVisible
                btn.message = Text.literal(if (CombatTrackerEngine.hudVisible) WowDpsI18n.tr("wowdps.btn.hud_shown", hideKey) else WowDpsI18n.tr("wowdps.btn.hud_hidden", hideKey))
            }.dimensions(centerX - 14, y1, 56, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.scale_down"))) {
                CombatTrackerEngine.adjustScale(-0.07f)
            }.dimensions(centerX + 44, y1, 44, 16).build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.scale_up"))) {
                CombatTrackerEngine.adjustScale(+0.07f)
            }.dimensions(centerX + 90, y1, 44, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.to_chat"))) {
                sendReportToChat()
            }.dimensions(centerX + 136, y1, 38, 16).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.reset"))) {
                CombatTrackerEngine.resetAll()
                selectedCombatantUuid = null
            }.dimensions(centerX + 176, y1, 38, 16).build()
        )

        // Row 2: Tabs including the new "🪄 Развёртка" (Weapon -> Spells breakdown)
        val y2 = 22
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.tab.weapon_tree"))) {
                CombatTrackerEngine.activeInspectorTab = InspectorTab.WEAPON_TREE
            }.dimensions(centerX - 210, y2, 74, 16).build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.tab.abilities"))) {
                CombatTrackerEngine.activeInspectorTab = InspectorTab.ABILITIES
            }.dimensions(centerX - 134, y2, 76, 16).build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.tab.targets"))) {
                CombatTrackerEngine.activeInspectorTab = InspectorTab.TARGETS
            }.dimensions(centerX - 56, y2, 52, 16).build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.tab.graphs"))) {
                CombatTrackerEngine.activeInspectorTab = InspectorTab.GRAPHS
            }.dimensions(centerX - 2, y2, 62, 16).build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal(WowDpsI18n.tr("wowdps.btn.graph_view", CombatTrackerEngine.activeGraphType.title))) { btn ->
                CombatTrackerEngine.activeInspectorTab = InspectorTab.GRAPHS
                CombatTrackerEngine.activeGraphType = CombatTrackerEngine.activeGraphType.next()
                btn.message = Text.literal(WowDpsI18n.tr("wowdps.btn.graph_view", CombatTrackerEngine.activeGraphType.title))
            }.dimensions(centerX + 62, y2, 148, 16).build()
        )

        if (CombatTrackerEngine.inspectorX < 0) {
            val hudRight = CombatTrackerEngine.hudX + (CombatTrackerEngine.hudWidth * CombatTrackerEngine.uiScale).toInt() + 10
            CombatTrackerEngine.inspectorX = max(hudRight, (width - CombatTrackerEngine.inspectorW) / 2)
                .coerceAtMost(max(10, width - CombatTrackerEngine.inspectorW - 8))
            CombatTrackerEngine.inspectorY = 42
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context)
        weaponClickZones.clear()

        if (CombatTrackerEngine.hudVisible) {
            lastRenderedHudHeight = WowDpsHudRenderer.renderMeterWindow(
                context = context,
                x = CombatTrackerEngine.hudX,
                y = CombatTrackerEngine.hudY,
                width = CombatTrackerEngine.hudWidth,
                height = CombatTrackerEngine.hudHeight,
                showResizeGrip = true
            )
        }

        renderMainReportWindow(context, mouseX, mouseY)

        super.render(context, mouseX, mouseY, delta)
    }

    private fun renderMainReportWindow(context: DrawContext, mouseX: Int, mouseY: Int) {
        val segment = CombatTrackerEngine.getActiveSegment()
        val sorted = segment.getSortedCombatants(CombatTrackerEngine.currentMode)
        val activeCombatant = sorted.firstOrNull { it.uuid == selectedCombatantUuid } ?: sorted.firstOrNull()

        val panelW = CombatTrackerEngine.inspectorW.coerceIn(250, max(260, width - 16))
        val panelH = CombatTrackerEngine.inspectorH.coerceIn(145, max(155, height - 48))
        val panelX = CombatTrackerEngine.inspectorX.coerceIn(4, max(6, width - panelW - 4))
        val panelY = CombatTrackerEngine.inspectorY.coerceIn(40, max(42, height - panelH - 4))

        context.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xEE0D1117.toInt())
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF2151A23.toInt())
        context.fillGradient(panelX, panelY, panelX + panelW, panelY + 18, 0xE6283244.toInt(), 0xE6181E29.toInt())
        context.fill(panelX, panelY + 18, panelX + panelW, panelY + 19, 0xAA8C7440.toInt())

        WowDpsHudRenderer.drawCornerResizeGrip(context, panelX + panelW, panelY + panelH)

        if (activeCombatant == null) {
            val emptyTitle = WowDpsHudRenderer.fitTextToPixels(
                textRenderer,
                WowDpsI18n.tr("wowdps.report.empty_title", CombatTrackerEngine.activeInspectorTab.title),
                panelW - 14,
                T_SCALE
            )
            WowDpsHudRenderer.drawScaledText(context, textRenderer, emptyTitle, panelX + 6, panelY + 5, 0xFFFFE6A3.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(
                context,
                textRenderer,
                WowDpsI18n.tr("wowdps.report.empty_hint"),
                panelX + 10,
                panelY + 32,
                0xFFCBD5E1.toInt(),
                T_SCALE
            )
            return
        }

        val dur = segment.durationSeconds
        val primaryAb = activeCombatant.getPrimaryAbility()
        WowDpsHudRenderer.drawAbilityOrSpellIcon(
            context = context,
            iconItemId = primaryAb?.iconItemId ?: activeCombatant.lastIconItemId,
            spellTextureId = primaryAb?.spellTextureId ?: activeCombatant.lastSpellTextureId,
            magicSchool = primaryAb?.magicSchool ?: activeCombatant.lastMagicSchool,
            hasGlint = primaryAb?.iconHasGlint ?: activeCombatant.lastIconHasGlint,
            x = panelX + 4,
            y = panelY + 2,
            scale = 0.85f
        )

        WowDpsHudRenderer.drawTargetMobIcon(
            context = context,
            entityTypeId = activeCombatant.lastTargetEntityTypeId,
            fallbackItemId = activeCombatant.lastTargetIconId,
            x = panelX + panelW - 18,
            y = panelY + 2,
            scale = 0.85f
        )

        val partyBadge = activeCombatant.getPartyBadge().let { if (it.isNotEmpty() && it != "★") " [$it]" else "" }
        val rawHeader = "${activeCombatant.name}$partyBadge [${activeCombatant.lastAbilityName}] | УВС: ${CombatTrackerEngine.formatCompactNumber(activeCombatant.getDps(dur))}/с | Крит: ${String.format("%.0f%%", activeCombatant.critPercent)}"
        val safeHeader = WowDpsHudRenderer.fitTextToPixels(textRenderer, rawHeader, panelW - 44, T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, safeHeader, panelX + 20, panelY + 6, 0xFFFFE6A3.toInt(), T_SCALE)

        val contentY = panelY + 21
        val contentH = panelH - 23

        when (CombatTrackerEngine.activeInspectorTab) {
            InspectorTab.WEAPON_TREE -> renderWeaponToSpellsTreeTab(context, activeCombatant, panelX, contentY, panelW, contentH)
            InspectorTab.ABILITIES -> renderAbilitiesWithIconsTab(context, activeCombatant, panelX, contentY, panelW, contentH)
            InspectorTab.TARGETS -> renderTargetsBreakdownTab(context, activeCombatant, dur, panelX, contentY, panelW, contentH)
            InspectorTab.GRAPHS -> {
                when (CombatTrackerEngine.activeGraphType) {
                    GraphType.BAR_TIMELINE -> renderBarTimelineGraph(context, activeCombatant, panelX, contentY, panelW, contentH, mouseX, mouseY)
                    GraphType.AREA_CURVE -> renderAreaCurveGraph(context, activeCombatant, panelX, contentY, panelW, contentH)
                    GraphType.DONUT_PIE -> renderDonutPieGraph(context, activeCombatant, panelX, contentY, panelW, contentH)
                    GraphType.HIT_HISTOGRAM -> renderHitAndTargetHistogram(context, activeCombatant, panelX, contentY, panelW, contentH)
                }
            }
        }
    }

    /**
     * NEW TAB (`🪄 Развёртка`): Hierarchical Expandable Breakdown of Weapon/Staff -> Released Spells!
     * Shows each Staff, Wand, Sword, or Bow used, and nested underneath it every Spell/Magic released from it.
     */
    private fun renderWeaponToSpellsTreeTab(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val innerW = w - 12
        val col1X = x + 6
        val col2X = col1X + (innerW * 0.50f).toInt()
        val col3X = col1X + (innerW * 0.67f).toInt()
        val col4X = col1X + (innerW * 0.83f).toInt()

        val col1W = col2X - col1X - 4
        val col2W = col3X - col2X - 4
        val col3W = col4X - col3X - 4
        val col4W = (x + w - 6) - col4X - 2

        val tableTop = y + 2
        context.fill(x + 6, tableTop, x + w - 6, tableTop + 12, 0xB310141B.toInt())
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.weapon_tree"), col1W, T_SCALE), col1X + 4, tableTop + 2, 0xFFFFE6A3.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.hits_crits"), col2W, T_SCALE), col2X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.avg_max"), col3W, T_SCALE), col3X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.damage_pct"), col4W, T_SCALE), col4X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)

        val weapons = combatant.getSortedWeapons()
        val totalPlayerDmg = max(0.0001, combatant.totalDamageDealt)
        var rowY = tableTop + 14

        for (wpn in weapons) {
            if (rowY + 14 > y + h) break

            val wpnPctOfTotal = (wpn.totalDamage / totalPlayerDmg) * 100.0
            val wpnFillW = (innerW * (wpnPctOfTotal / 100.0)).toInt()

            // Weapon Parent Header Row (Dark gold/amber-tinted bar, clickable to expand/collapse)
            context.fill(x + 6, rowY, x + w - 6, rowY + 14, 0xCC1B202B.toInt())
            context.fillGradient(x + 6, rowY, x + 6 + wpnFillW, rowY + 14, 0xAA6D4C23.toInt(), 0xAA432E14.toInt())
            context.fill(x + 6, rowY, x + w - 6, rowY + 1, 0x77FBBF24)

            weaponClickZones.add(WeaponHeaderClickZone(wpn.weaponName, rowY, rowY + 14))

            val arrowSymbol = if (wpn.expanded) "▼" else "▶"
            WowDpsHudRenderer.drawScaledText(context, textRenderer, arrowSymbol, col1X + 2, rowY + 3, 0xFFFDE047.toInt(), T_SCALE)

            val wpnStack = CombatTrackerEngine.getIconStack(wpn.weaponIconId, wpn.weaponGlint)
            WowDpsHudRenderer.drawScaledItemIcon(context, wpnStack, col1X + 10, rowY + 1, 0.72f)

            val spellCount = wpn.releasedSpells.size
            val wpnTitleRaw = WowDpsI18n.tr("wowdps.wpn.spells_count", wpn.weaponName, spellCount)
            val wpnTitleSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, wpnTitleRaw, col1W - 24, T_SCALE)
            val wpnCritPct = if (wpn.totalHits > 0) (wpn.totalCrits.toDouble() / wpn.totalHits.toDouble()) * 100.0 else 0.0
            val wpnHitsSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${wpn.totalHits} (${String.format("%.0f%%", wpnCritPct)})", col2W, T_SCALE)
            val wpnDmgSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${CombatTrackerEngine.formatCompactNumber(wpn.totalDamage)} (${String.format("%.0f%%", wpnPctOfTotal)})", col4W, T_SCALE)

            WowDpsHudRenderer.drawScaledText(context, textRenderer, wpnTitleSafe, col1X + 23, rowY + 3, 0xFFFFE6A3.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, wpnHitsSafe, col2X, rowY + 3, 0xFFE2E8F0.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, wpnDmgSafe, col4X, rowY + 3, 0xFFFDE047.toInt(), T_SCALE)

            rowY += 15

            // Child Rows: Every Spell / Magic released from this Staff / Sword!
            if (wpn.expanded) {
                val spells = wpn.getSortedSpells()
                val wpnTotal = max(0.0001, wpn.totalDamage)
                for (sIdx in spells.indices) {
                    if (rowY + 13 > y + h) break
                    val sp = spells[sIdx]
                    val pctOfWpn = (sp.totalDamage / wpnTotal) * 100.0
                    val spBarW = ((innerW - 14) * (pctOfWpn / 100.0)).toInt()

                    context.fill(x + 16, rowY, x + w - 6, rowY + 13, 0x8010141D.toInt())
                    val schoolCol = WowDpsHudRenderer.getMagicSchoolColor(sp.magicSchool) and 0xFFFFFF
                    context.fillGradient(
                        x + 16,
                        rowY,
                        x + 16 + spBarW,
                        rowY + 13,
                        (0x88 shl 24) or schoolCol,
                        (0x55 shl 24) or schoolCol
                    )

                    val treeBranch = if (sIdx == spells.lastIndex) "└─" else "├─"
                    WowDpsHudRenderer.drawScaledText(context, textRenderer, treeBranch, col1X + 4, rowY + 3, 0xFF94A3B8.toInt(), 0.75f)

                    // Draw exact Spell Icon (PNG spell texture or glowing rune/elemental icon)
                    WowDpsHudRenderer.drawAbilityOrSpellIcon(
                        context = context,
                        iconItemId = sp.iconItemId,
                        spellTextureId = sp.spellTextureId,
                        magicSchool = sp.magicSchool,
                        hasGlint = sp.iconHasGlint,
                        x = col1X + 16,
                        y = rowY + 1,
                        scale = 0.68f
                    )

                    val spNameSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, sp.abilityName, col1W - 30, T_SCALE)
                    val spHitsSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${sp.hits} (${String.format("%.0f%%", sp.critRatePercent)})", col2W, T_SCALE)
                    val spAvgMaxSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${CombatTrackerEngine.formatCompactNumber(sp.avgHit)}/${CombatTrackerEngine.formatCompactNumber(sp.maxHit.toDouble())}", col3W, T_SCALE)
                    val spDmgSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${CombatTrackerEngine.formatCompactNumber(sp.totalDamage)} (${String.format("%.0f%%", pctOfWpn)})", col4W, T_SCALE)

                    WowDpsHudRenderer.drawScaledText(context, textRenderer, spNameSafe, col1X + 29, rowY + 3, 0xFFFFFFFF.toInt(), T_SCALE)
                    WowDpsHudRenderer.drawScaledText(context, textRenderer, spHitsSafe, col2X, rowY + 3, 0xFFE2E8F0.toInt(), T_SCALE)
                    WowDpsHudRenderer.drawScaledText(context, textRenderer, spAvgMaxSafe, col3X, rowY + 3, 0xFFFDE047.toInt(), T_SCALE)
                    WowDpsHudRenderer.drawScaledText(context, textRenderer, spDmgSafe, col4X, rowY + 3, 0xFF86EFAC.toInt(), T_SCALE)

                    rowY += 14
                }
            }
        }
    }

    /**
     * TAB 2 (`🔮 Заклинания`): All Spells & Magic Ranked by Damage, showing both Spell Icon and Source Staff/Sword
     */
    private fun renderAbilitiesWithIconsTab(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val innerW = w - 12
        val col1X = x + 6
        val col2X = col1X + (innerW * 0.44f).toInt()
        val col3X = col1X + (innerW * 0.68f).toInt()
        val col4X = col1X + (innerW * 0.83f).toInt()

        val col1W = col2X - col1X - 4
        val col2W = col3X - col2X - 4
        val col3W = col4X - col3X - 4
        val col4W = (x + w - 6) - col4X - 2

        val tableTop = y + 2
        context.fill(x + 6, tableTop, x + w - 6, tableTop + 12, 0xB310141B.toInt())
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.spell_magic"), col1W, T_SCALE), col1X + 4, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.released_from"), col2W, T_SCALE), col2X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.hits_max"), col3W, T_SCALE), col3X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.damage_pct"), col4W, T_SCALE), col4X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)

        val abilities = combatant.getSortedAbilities()
        val totalPlayerDmg = max(0.0001, combatant.totalDamageDealt)
        var rowY = tableTop + 14
        val maxRows = min(abilities.size, (h - 18) / 15)

        for (i in 0 until maxRows) {
            val ab = abilities[i]
            val pct = (ab.totalDamage / totalPlayerDmg) * 100.0
            val barFillW = (innerW * (pct / 100.0)).toInt()

            context.fill(x + 6, rowY, x + w - 6, rowY + 14, 0x8012161F.toInt())
            val schoolCol = WowDpsHudRenderer.getMagicSchoolColor(ab.magicSchool) and 0xFFFFFF
            val barColor = if (ab.magicSchool != "physical") (0x88 shl 24) or schoolCol else 0x993B5998.toInt()
            context.fill(x + 6, rowY, x + 6 + barFillW, rowY + 14, barColor)

            // Column 1: Spell Icon + Clear Spell Name
            WowDpsHudRenderer.drawAbilityOrSpellIcon(context, ab.iconItemId, ab.spellTextureId, ab.magicSchool, ab.iconHasGlint, col1X + 2, rowY + 1, 0.72f)
            val c1 = WowDpsHudRenderer.fitTextToPixels(textRenderer, ab.abilityName, col1W - 16, T_SCALE)

            // Column 2: Source Staff / Sword Icon + Name (WowDpsI18n.tr("wowdps.col.released_from"))
            val wpnStack = CombatTrackerEngine.getIconStack(ab.sourceWeaponIconId, false)
            WowDpsHudRenderer.drawScaledItemIcon(context, wpnStack, col2X, rowY + 1, 0.68f)
            val c2 = WowDpsHudRenderer.fitTextToPixels(textRenderer, ab.sourceWeaponName.ifEmpty { "—" }, col2W - 13, T_SCALE)

            val c3 = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${ab.hits}/${CombatTrackerEngine.formatCompactNumber(ab.maxHit.toDouble())}", col3W, T_SCALE)
            val c4 = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${CombatTrackerEngine.formatCompactNumber(ab.totalDamage)} (${String.format("%.0f%%", pct)})", col4W, T_SCALE)

            WowDpsHudRenderer.drawScaledText(context, textRenderer, c1, col1X + 16, rowY + 3, 0xFFFFFFFF.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c2, col2X + 12, rowY + 3, 0xFFCBD5E1.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c3, col3X, rowY + 3, 0xFFFDE047.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c4, col4X, rowY + 3, 0xFF86EFAC.toInt(), T_SCALE)

            rowY += 15
        }
    }

    private fun renderBarTimelineGraph(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val rawKpi = "Всего: ${CombatTrackerEngine.formatCompactNumber(combatant.totalDamageDealt)} | Ударов: ${combatant.totalHits} | Пик: ${CombatTrackerEngine.formatCompactNumber(combatant.peakBurstDps)}/с | Блок: ${CombatTrackerEngine.formatCompactNumber(combatant.totalMitigated)}"
        val safeKpi = WowDpsHudRenderer.fitTextToPixels(textRenderer, rawKpi, w - 12, T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, safeKpi, x + 6, y + 2, 0xFFCBD5E1.toInt(), T_SCALE)

        val chartX = x + 32
        val chartY = y + 13
        val chartW = (w - 40).coerceAtLeast(60)
        val chartH = (h - 68).coerceIn(45, 160)

        context.fill(chartX - 1, chartY - 1, chartX + chartW + 1, chartY + chartH + 1, 0xCC0B0E14.toInt())
        context.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xD910151E.toInt())

        val series = combatant.getTimelineSeries(40)
        val maxVal = max(1.0, series.maxOfOrNull { max(it.damageInSecond, it.cumulativeDps) } ?: 10.0)

        for (g in 0..2) {
            val gy = chartY + (chartH * g) / 2
            context.fill(chartX, gy, chartX + chartW, gy + 1, 0x44334155)
            val labelVal = maxVal * (1.0 - g * 0.5)
            val labelStr = CombatTrackerEngine.formatCompactNumber(labelVal)
            val lw = WowDpsHudRenderer.getScaledTextWidth(textRenderer, labelStr, 0.72f)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, labelStr, chartX - lw - 3, gy - 2, 0xFF94A3B8.toInt(), 0.72f)
        }

        val count = max(1, series.size)
        val stepW = chartW.toFloat() / count.toFloat()
        var hoveredPoint: TimelinePoint? = null
        var prevX = -1
        var prevY = -1

        for (i in series.indices) {
            val pt = series[i]
            val bx1 = (chartX + i * stepW).toInt()
            val bx2 = max(bx1 + 2, (chartX + (i + 1) * stepW - 1).toInt())

            val normH = (pt.damageInSecond / maxVal).coerceIn(0.0, 1.0)
            val barH = (normH * (chartH - 4)).toInt()
            val by1 = chartY + chartH - barH

            if (barH > 0) {
                val topCol = if (pt.critHitsInSecond > 0) 0xE6F59E0B.toInt() else 0xCC3B82F6.toInt()
                val botCol = if (pt.critHitsInSecond > 0) 0x99B45309.toInt() else 0x801D4ED8.toInt()
                context.fillGradient(bx1, by1, bx2, chartY + chartH, topCol, botCol)
            }

            val avgNorm = (pt.cumulativeDps / maxVal).coerceIn(0.0, 1.0)
            val nx = (bx1 + bx2) / 2
            val ny = chartY + chartH - (avgNorm * (chartH - 4)).toInt()
            if (prevX != -1) {
                drawLineSegment(context, prevX, prevY, nx, ny, 0xFF10B981.toInt())
            }
            context.fill(nx - 1, ny - 1, nx + 2, ny + 2, 0xFF34D399.toInt())
            prevX = nx
            prevY = ny

            if (mouseX in bx1..bx2 && mouseY in chartY..(chartY + chartH)) {
                hoveredPoint = pt
                context.fill(bx1, chartY, bx2, chartY + chartH, 0x33FFFFFF)
            }
        }

        val legend = WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.graph.bar_legend"), chartW, 0.75f)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, legend, chartX + 4, chartY + chartH + 2, 0xFFCBD5E1.toInt(), 0.75f)

        var rowY = chartY + chartH + 12
        val abilities = combatant.getSortedAbilities().take(3)
        val totalDmg = max(0.0001, combatant.totalDamageDealt)
        for (ab in abilities) {
            if (rowY + 12 > y + h) break
            val pct = (ab.totalDamage / totalDmg) * 100.0
            val barW = ((w - 12) * (pct / 100.0)).toInt()
            context.fill(x + 6, rowY, x + w - 6, rowY + 12, 0x8010141C.toInt())
            context.fillGradient(x + 6, rowY, x + 6 + barW, rowY + 12, 0xCC2563EB.toInt(), 0xCC1E40AF.toInt())

            WowDpsHudRenderer.drawAbilityOrSpellIcon(context, ab.iconItemId, ab.spellTextureId, ab.magicSchool, ab.iconHasGlint, x + 8, rowY, 0.68f)

            val rightStr = "${CombatTrackerEngine.formatCompactNumber(ab.totalDamage)} (${String.format("%.0f%%", pct)})"
            val rightW = WowDpsHudRenderer.getScaledTextWidth(textRenderer, rightStr, T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, rightStr, x + w - rightW - 9, rowY + 2, 0xFF86EFAC.toInt(), T_SCALE)

            val srcWpn = if (ab.sourceWeaponName.isNotEmpty()) " [из: ${ab.sourceWeaponName}]" else ""
            val leftRaw = "${ab.abilityName}$srcWpn"
            val leftSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, leftRaw, w - rightW - 34, T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, leftSafe, x + 21, rowY + 2, 0xFFFFFFFF.toInt(), T_SCALE)
            rowY += 13
        }

        if (hoveredPoint != null) {
            val tipRaw = "${hoveredPoint.secondIndex}с: ${CombatTrackerEngine.formatCompactNumber(hoveredPoint.damageInSecond)} | ${hoveredPoint.topAbilityInSecond.ifEmpty { "—" }} → ${hoveredPoint.targetInSecond.ifEmpty { "—" }}"
            val tipSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, tipRaw, w - 16, T_SCALE)
            val tw = WowDpsHudRenderer.getScaledTextWidth(textRenderer, tipSafe, T_SCALE) + 8
            val tx = (mouseX + 6).coerceAtMost(width - tw - 4)
            val ty = max(chartY, mouseY - 14)
            context.fill(tx, ty, tx + tw, ty + 12, 0xF00F172A.toInt())
            WowDpsHudRenderer.drawScaledText(context, textRenderer, tipSafe, tx + 4, ty + 2, 0xFFFDE047.toInt(), T_SCALE)
        }
    }

    private fun renderAreaCurveGraph(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val header = WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.graph.area_title"), w - 12, T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, header, x + 6, y + 2, 0xFFCBD5E1.toInt(), T_SCALE)

        val chartX = x + 32
        val chartY = y + 14
        val chartW = (w - 40).coerceAtLeast(60)
        val chartH = (h - 30).coerceIn(50, 220)

        context.fill(chartX - 1, chartY - 1, chartX + chartW + 1, chartY + chartH + 1, 0xCC0B0E14.toInt())
        context.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xD910151E.toInt())

        val series = combatant.getTimelineSeries(40)
        val maxVal = max(1.0, series.maxOfOrNull { max(it.damageInSecond + it.mitigatedInSecond, it.cumulativeDps) } ?: 10.0)

        for (g in 0..2) {
            val gy = chartY + (chartH * g) / 2
            context.fill(chartX, gy, chartX + chartW, gy + 1, 0x44334155)
            val labelVal = maxVal * (1.0 - g * 0.5)
            val labelStr = CombatTrackerEngine.formatCompactNumber(labelVal)
            val lw = WowDpsHudRenderer.getScaledTextWidth(textRenderer, labelStr, 0.72f)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, labelStr, chartX - lw - 3, gy - 2, 0xFF94A3B8.toInt(), 0.72f)
        }

        val count = max(2, series.size)
        val stepW = chartW.toFloat() / (count - 1).toFloat()

        for (i in 0 until count - 1) {
            val p1 = series[i]
            val p2 = series[i + 1]
            val x1 = (chartX + i * stepW).toInt()
            val x2 = (chartX + (i + 1) * stepW).toInt()

            val totY1 = chartY + chartH - ((p1.damageInSecond / maxVal).coerceIn(0.0, 1.0) * (chartH - 4)).toInt()
            val totY2 = chartY + chartH - ((p2.damageInSecond / maxVal).coerceIn(0.0, 1.0) * (chartH - 4)).toInt()

            val critY1 = chartY + chartH - ((p1.critDamageInSecond / maxVal).coerceIn(0.0, 1.0) * (chartH - 4)).toInt()
            val critY2 = chartY + chartH - ((p2.critDamageInSecond / maxVal).coerceIn(0.0, 1.0) * (chartH - 4)).toInt()

            val spanX = max(1, x2 - x1)
            for (sx in x1..x2) {
                val t = (sx - x1).toFloat() / spanX.toFloat()
                val curTotY = (totY1 + (totY2 - totY1) * t).toInt()
                val curCritY = (critY1 + (critY2 - critY1) * t).toInt()
                context.fill(sx, curTotY, sx + 1, chartY + chartH, 0x6606B6D4)
                context.fill(sx, curCritY, sx + 1, chartY + chartH, 0x88F59E0B.toInt())
            }

            drawLineSegment(context, x1, totY1, x2, totY2, 0xFF22D3EE.toInt())
            drawLineSegment(context, x1, critY1, x2, critY2, 0xFFFBBF24.toInt())
        }

        val legend = WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.graph.area_legend"), chartW, T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, legend, chartX + 4, chartY + chartH + 4, 0xFFE2E8F0.toInt(), T_SCALE)
    }

    private fun renderDonutPieGraph(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val halfW = (w - 16) / 2
        val leftX = x + 6
        val rightX = x + 10 + halfW

        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsI18n.tr("wowdps.graph.donut_spells"), leftX, y + 2, 0xFFFFE6A3.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsI18n.tr("wowdps.graph.donut_targets"), rightX, y + 2, 0xFF93C5FD.toInt(), T_SCALE)

        val outerR = min(28, (h - 40) / 3).coerceAtLeast(16)
        val innerR = (outerR * 0.45f).toInt()
        val donutY = y + 14 + outerR

        val abilities = combatant.getSortedAbilities().take(5)
        val targets = combatant.getSortedTargets().take(5)

        val leftCx = leftX + outerR + 2
        val abilityShares = abilities.map { it.totalDamage }
        drawDonutChart(context, leftCx, donutY, outerR, innerR, abilityShares)

        val totalAbDmg = max(0.0001, abilityShares.sum())
        val leftLegStartX = leftCx + outerR + 6
        val leftLegMaxW = (leftX + halfW) - leftLegStartX - 2
        var legY = y + 15
        for (i in abilities.indices) {
            val ab = abilities[i]
            val pct = (ab.totalDamage / totalAbDmg) * 100.0
            val col = CombatTrackerEngine.CHART_SLICE_COLORS[i % CombatTrackerEngine.CHART_SLICE_COLORS.size]
            context.fill(leftLegStartX, legY + 2, leftLegStartX + 5, legY + 7, col)
            WowDpsHudRenderer.drawAbilityOrSpellIcon(context, ab.iconItemId, ab.spellTextureId, ab.magicSchool, ab.iconHasGlint, leftLegStartX + 6, legY - 1, 0.62f)

            val rawTxt = "${String.format("%.0f%%", pct)} ${ab.abilityName}"
            val safeTxt = WowDpsHudRenderer.fitTextToPixels(textRenderer, rawTxt, leftLegMaxW - 18, 0.75f)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, safeTxt, leftLegStartX + 18, legY + 1, 0xFFE2E8F0.toInt(), 0.75f)
            legY += 13
        }

        context.fill(rightX - 4, y + 2, rightX - 3, y + h - 4, 0x44475569)

        val rightCx = rightX + outerR + 2
        val targetShares = targets.map { it.totalDamage }
        drawDonutChart(context, rightCx, donutY, outerR, innerR, targetShares)

        val totalTgtDmg = max(0.0001, targetShares.sum())
        val rightLegStartX = rightCx + outerR + 6
        val rightLegMaxW = (rightX + halfW) - rightLegStartX - 2
        var rLegY = y + 15
        for (i in targets.indices) {
            val t = targets[i]
            val pct = (t.totalDamage / totalTgtDmg) * 100.0
            val col = CombatTrackerEngine.CHART_SLICE_COLORS[i % CombatTrackerEngine.CHART_SLICE_COLORS.size]
            context.fill(rightLegStartX, rLegY + 2, rightLegStartX + 5, rLegY + 7, col)
            WowDpsHudRenderer.drawTargetMobIcon(context, t.targetEntityTypeId, t.targetIconItemId, rightLegStartX + 6, rLegY - 1, 0.62f)

            val rawTxt = "${String.format("%.0f%%", pct)} ${t.targetName}"
            val safeTxt = WowDpsHudRenderer.fitTextToPixels(textRenderer, rawTxt, rightLegMaxW - 18, 0.75f)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, safeTxt, rightLegStartX + 18, rLegY + 1, 0xFFE2E8F0.toInt(), 0.75f)
            rLegY += 13
        }
    }

    private fun drawDonutChart(
        context: DrawContext,
        cx: Int,
        cy: Int,
        outerRadius: Int,
        innerRadius: Int,
        values: List<Double>
    ) {
        val sum = values.sum()
        if (sum <= 0.0001) return

        val cumulativeAngles = DoubleArray(values.size)
        var acc = 0.0
        for (i in values.indices) {
            acc += (values[i] / sum) * (2.0 * PI)
            cumulativeAngles[i] = acc
        }

        val step = 2
        for (dy in -outerRadius..outerRadius step step) {
            for (dx in -outerRadius..outerRadius step step) {
                val dist = hypot(dx.toDouble(), dy.toDouble())
                if (dist >= innerRadius && dist <= outerRadius) {
                    var angle = atan2(dy.toDouble(), dx.toDouble()) + PI / 2.0
                    if (angle < 0) angle += 2.0 * PI
                    var sliceIdx = cumulativeAngles.indexOfFirst { angle <= it }
                    if (sliceIdx == -1) sliceIdx = values.lastIndex
                    val color = CombatTrackerEngine.CHART_SLICE_COLORS[sliceIdx % CombatTrackerEngine.CHART_SLICE_COLORS.size]
                    context.fill(cx + dx, cy + dy, cx + dx + step, cy + dy + step, color)
                }
            }
        }
    }

    private fun renderHitAndTargetHistogram(
        context: DrawContext,
        combatant: CombatantStats,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsI18n.tr("wowdps.graph.hit_title"), x + 6, y + 2, 0xFFFFE6A3.toInt(), T_SCALE)

        val abilities = combatant.getSortedAbilities().take(4)
        val overallMaxHit = max(1f, abilities.maxOfOrNull { it.maxHit } ?: 10f).toDouble()

        val labelColW = (w * 0.34f).toInt()
        val trackX = x + labelColW + 6
        val trackW = (w - labelColW - 14).coerceAtLeast(50)

        var rowY = y + 13
        for (ab in abilities) {
            val minVal = if (ab.minHit == Float.MAX_VALUE) 0.0 else ab.minHit.toDouble()
            val avgVal = ab.avgHit
            val maxVal = ab.maxHit.toDouble()

            WowDpsHudRenderer.drawAbilityOrSpellIcon(context, ab.iconItemId, ab.spellTextureId, ab.magicSchool, ab.iconHasGlint, x + 6, rowY, 0.70f)

            val safeName = WowDpsHudRenderer.fitTextToPixels(textRenderer, ab.abilityName, labelColW - 16, T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, safeName, x + 19, rowY + 2, 0xFFFFFFFF.toInt(), T_SCALE)

            context.fill(trackX, rowY + 1, trackX + trackW, rowY + 11, 0x990F141C.toInt())

            val maxW = ((maxVal / overallMaxHit) * trackW).toInt()
            val avgW = ((avgVal / overallMaxHit) * trackW).toInt()
            val minW = ((minVal / overallMaxHit) * trackW).toInt()

            context.fill(trackX, rowY + 1, trackX + maxW, rowY + 11, 0x99D97706.toInt())
            context.fill(trackX, rowY + 1, trackX + avgW, rowY + 11, 0xCC10B981.toInt())
            context.fill(trackX + minW, rowY + 1, trackX + minW + 2, rowY + 11, 0xFF38BDF8.toInt())

            val rawStat = "Мин:${CombatTrackerEngine.formatCompactNumber(minVal)} Ср:${CombatTrackerEngine.formatCompactNumber(avgVal)} Макс:${CombatTrackerEngine.formatCompactNumber(maxVal)}"
            val safeStat = WowDpsHudRenderer.fitTextToPixels(textRenderer, rawStat, trackW - 6, 0.75f)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, safeStat, trackX + 3, rowY + 2, 0xFFF8FAFC.toInt(), 0.75f)

            rowY += 13
        }

        rowY += 4
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsI18n.tr("wowdps.graph.target_compare"), x + 6, rowY, 0xFF93C5FD.toInt(), T_SCALE)
        rowY += 11

        val targets = combatant.getSortedTargets().take(4)
        val topTargetDmg = max(0.0001, targets.firstOrNull()?.totalDamage ?: 1.0)
        val totalDmg = max(0.0001, combatant.totalDamageDealt)

        for (i in targets.indices) {
            if (rowY + 13 > y + h) break
            val t = targets[i]
            val ratio = (t.totalDamage / topTargetDmg).coerceIn(0.02, 1.0)
            val pct = (t.totalDamage / totalDmg) * 100.0
            val fillW = ((w - 12) * ratio).toInt()

            context.fill(x + 6, rowY, x + w - 6, rowY + 13, 0x8010141C.toInt())
            val col = CombatTrackerEngine.CHART_SLICE_COLORS[i % CombatTrackerEngine.CHART_SLICE_COLORS.size]
            context.fill(x + 6, rowY, x + 6 + fillW, rowY + 13, (col and 0x00FFFFFF) or (0xAA shl 24))

            WowDpsHudRenderer.drawTargetMobIcon(context, t.targetEntityTypeId, t.targetIconItemId, x + 8, rowY + 1, 0.68f)

            val rightTxt = "${CombatTrackerEngine.formatCompactNumber(t.totalDamage)} (${String.format("%.0f%%", pct)}) | ${t.hits} уд."
            val rightW = WowDpsHudRenderer.getScaledTextWidth(textRenderer, rightTxt, T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, rightTxt, x + w - rightW - 9, rowY + 3, 0xFF86EFAC.toInt(), T_SCALE)

            val leftRaw = "${t.targetName} [${t.targetTypeName}]"
            val leftSafe = WowDpsHudRenderer.fitTextToPixels(textRenderer, leftRaw, w - rightW - 32, T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, leftSafe, x + 21, rowY + 3, 0xFFFFFFFF.toInt(), T_SCALE)
            rowY += 14
        }
    }

    private fun renderTargetsBreakdownTab(
        context: DrawContext,
        combatant: CombatantStats,
        durationSec: Double,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val innerW = w - 12
        val col1X = x + 6
        val col2X = col1X + (innerW * 0.42f).toInt()
        val col3X = col1X + (innerW * 0.66f).toInt()
        val col4X = col1X + (innerW * 0.82f).toInt()

        val col1W = col2X - col1X - 4
        val col2W = col3X - col2X - 4
        val col3W = col4X - col3X - 4
        val col4W = (x + w - 6) - col4X - 2

        val tableTop = y + 2
        context.fill(x + 6, tableTop, x + w - 6, tableTop + 12, 0xB310141B.toInt())
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.target_damaged"), col1W, T_SCALE), col1X + 4, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.last_magic"), col2W, T_SCALE), col2X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.hits_max"), col3W, T_SCALE), col3X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)
        WowDpsHudRenderer.drawScaledText(context, textRenderer, WowDpsHudRenderer.fitTextToPixels(textRenderer, WowDpsI18n.tr("wowdps.col.damage_dps"), col4W, T_SCALE), col4X, tableTop + 2, 0xFF93C5FD.toInt(), T_SCALE)

        val targets = combatant.getSortedTargets()
        val totalPlayerDmg = max(0.0001, combatant.totalDamageDealt)
        var rowY = tableTop + 14
        val maxRows = min(targets.size, (h - 18) / 15)

        for (i in 0 until maxRows) {
            val t = targets[i]
            val pct = (t.totalDamage / totalPlayerDmg) * 100.0
            val barFillW = (innerW * (pct / 100.0)).toInt()

            context.fill(x + 6, rowY, x + w - 6, rowY + 14, 0x8012161F.toInt())
            val barColor = if (t.isBoss) 0x997F1D1D.toInt() else 0x991E3A8A.toInt()
            context.fill(x + 6, rowY, x + 6 + barFillW, rowY + 14, barColor)

            WowDpsHudRenderer.drawTargetMobIcon(context, t.targetEntityTypeId, t.targetIconItemId, col1X + 2, rowY + 1, 0.72f)
            WowDpsHudRenderer.drawAbilityOrSpellIcon(context, t.lastWeaponIconId, t.lastSpellTextureId, t.lastMagicSchool, false, col2X, rowY + 1, 0.70f)

            val targetTitle = if (t.targetName != t.targetTypeName) "${t.targetName} [${t.targetTypeName}]" else t.targetName
            val c1 = WowDpsHudRenderer.fitTextToPixels(textRenderer, targetTitle, col1W - 16, T_SCALE)
            val c2 = WowDpsHudRenderer.fitTextToPixels(textRenderer, t.lastWeaponUsed, col2W - 14, T_SCALE)
            val c3 = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${t.hits}/${CombatTrackerEngine.formatCompactNumber(t.maxHit.toDouble())}", col3W, T_SCALE)
            val targetDps = CombatTrackerEngine.formatCompactNumber(t.totalDamage / max(1.0, durationSec))
            val c4 = WowDpsHudRenderer.fitTextToPixels(textRenderer, "${CombatTrackerEngine.formatCompactNumber(t.totalDamage)} ($targetDps/с)", col4W, T_SCALE)

            WowDpsHudRenderer.drawScaledText(context, textRenderer, c1, col1X + 16, rowY + 3, 0xFFFFFFFF.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c2, col2X + 13, rowY + 3, 0xFFCBD5E1.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c3, col3X, rowY + 3, 0xFFFDE047.toInt(), T_SCALE)
            WowDpsHudRenderer.drawScaledText(context, textRenderer, c4, col4X, rowY + 3, 0xFF86EFAC.toInt(), T_SCALE)

            rowY += 15
        }
    }

    private fun drawLineSegment(context: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val steps = max(1, max(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)))
        for (s in 0..steps) {
            val px = x1 + ((x2 - x1) * s) / steps
            val py = y1 + ((y2 - y1) * s) / steps
            context.fill(px, py, px + 2, py + 2, color)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true

        // 1. Delegate small HUD window click/resize/drag/select to WowDpsHudRenderer
        if (WowDpsHudRenderer.handleHudMouseClicked(mouseX, mouseY, button)) {
            if (WowDpsHudRenderer.selectedHudCombatantUuid != null) {
                selectedCombatantUuid = WowDpsHudRenderer.selectedHudCombatantUuid
            }
            return true
        }

        val ix = CombatTrackerEngine.inspectorX
        val iy = CombatTrackerEngine.inspectorY
        val iw = CombatTrackerEngine.inspectorW
        val ih = CombatTrackerEngine.inspectorH

        if (mouseX >= ix + iw - 14 && mouseX <= ix + iw + 4 &&
            mouseY >= iy + ih - 14 && mouseY <= iy + ih + 4
        ) {
            resizingInspector = true
            return true
        }

        if (mouseX >= ix && mouseX <= ix + iw && mouseY >= iy && mouseY <= iy + 18) {
            draggingInspector = true
            inspDragOffsetX = (mouseX - ix).toInt()
            inspDragOffsetY = (mouseY - iy).toInt()
            return true
        }

        // Check if clicking a Weapon Header row in WEAPON_TREE tab to expand/collapse its spells
        if (CombatTrackerEngine.activeInspectorTab == InspectorTab.WEAPON_TREE &&
            mouseX >= ix + 6 && mouseX <= ix + iw - 6
        ) {
            val segment = CombatTrackerEngine.getActiveSegment()
            val sorted = segment.getSortedCombatants(CombatTrackerEngine.currentMode)
            val activeCombatant = sorted.firstOrNull { it.uuid == selectedCombatantUuid } ?: sorted.firstOrNull()
            if (activeCombatant != null) {
                for (zone in weaponClickZones) {
                    if (mouseY >= zone.y1 && mouseY <= zone.y2) {
                        val wpnGroup = activeCombatant.weaponBreakdowns[zone.weaponName]
                        if (wpnGroup != null) {
                            wpnGroup.expanded = !wpnGroup.expanded
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (WowDpsHudRenderer.handleHudMouseDragged(mouseX, mouseY, width, height)) {
            return true
        }
        if (resizingInspector) {
            CombatTrackerEngine.inspectorW = (mouseX.toInt() - CombatTrackerEngine.inspectorX).coerceIn(250, max(260, width - CombatTrackerEngine.inspectorX - 6))
            CombatTrackerEngine.inspectorH = (mouseY.toInt() - CombatTrackerEngine.inspectorY).coerceIn(145, max(155, height - CombatTrackerEngine.inspectorY - 6))
            return true
        }
        if (draggingInspector) {
            CombatTrackerEngine.inspectorX = (mouseX.toInt() - inspDragOffsetX).coerceIn(4, max(6, width - CombatTrackerEngine.inspectorW - 4))
            CombatTrackerEngine.inspectorY = (mouseY.toInt() - inspDragOffsetY).coerceIn(40, max(42, height - CombatTrackerEngine.inspectorH - 4))
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        WowDpsHudRenderer.handleHudMouseReleased()
        draggingInspector = false
        resizingInspector = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    private fun sendReportToChat() {
        val player = client?.player ?: return
        val segment = CombatTrackerEngine.getActiveSegment()
        val mode = CombatTrackerEngine.currentMode
        val sorted = segment.getSortedCombatants(mode)
        if (sorted.isEmpty()) return

        val dur = segment.durationSeconds
        val groupTotal = max(0.0001, segment.getTotalGroupMetric(mode))
        val topList = sorted.take(5).mapIndexed { idx, c ->
            val dps = CombatTrackerEngine.formatCompactNumber(c.getDps(dur))
            val tot = CombatTrackerEngine.formatCompactNumber(c.totalDamageDealt)
            val pct = (c.getMetricValue(mode, dur) / groupTotal) * 100.0
            val secUnit = if (WowDpsI18n.isRussian()) "/с" else "/s"
            "#${idx + 1} ${c.name} [${c.lastAbilityName} → ${c.lastTargetName}]: $tot ($dps$secUnit, ${String.format("%.0f%%", pct)})"
        }.joinToString(" | ")

        val summaryLine = "[Details! ${mode.displayName} - ${CombatTrackerEngine.formatDuration(dur)}] $topList"
        player.networkHandler.sendChatMessage(summaryLine)
    }

    override fun shouldPause(): Boolean = false
}
