package dev.wowdps.client

import dev.wowdps.team.PartyTeamIntegration
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Freely Resizable & Auto-Adapting WoW Details! Small HUD Window (v7.0 — Prominence II Edition).
 *
 * Key v7.0 Upgrades:
 * 1) Freely Resizable in BOTH Width (`hudWidth`) and Height (`hudHeight`) via the `◢` bottom-right corner grip
 *    or window edges — works both inside the Inspector (`[K]`) AND directly when Chat (`[T]`) is open!
 * 2) True Responsive Auto-Adaptation (`подстраивается`):
 *    - Automatically adapts font scale, icon scale, bar height, and column detail level (Compact / Standard / Wide)
 *      based on current window width & height.
 *    - When stretched vertically beyond the player list, automatically reveals adaptive sub-panels right inside
 *      the small HUD window:
 *        a) `🪄 Топ заклинаний / оружия` (live spell & staff breakdown of the active player/teammate)
 *        b) `📈 Динамика УВС` (live mini DPS bar & curve graph inside the small HUD window)
 * 3) Full FTB Teams & Open Parties and Claims (`OPAC`) teammate badges (`🤝 [FTB]`, `🤝 [OPAC]`, `★`)
 *    and quick header filter toggle (`[Все]` / `[Пати]` / `[Я]`).
 */
object WowDpsHudRenderer : HudRenderCallback {

    const val HEADER_HEIGHT = 14
    const val BAR_HEIGHT = 13
    const val BAR_GAP = 1
    const val FOOTER_HEIGHT = 12

    // Interactive HUD state used both in ChatScreen (or any unlocked cursor screen) and in WowDpsInspectorScreen
    var draggingHud: Boolean = false
    var resizingHud: Boolean = false
    var hudDragOffsetX: Int = 0
    var hudDragOffsetY: Int = 0
    var lastRenderedHudHeight: Int = 115
    var selectedHudCombatantUuid: UUID? = null

    private val textureExistsCache = HashMap<String, Identifier?>()

    override fun onHudRender(context: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        if (client.options.debugEnabled || !CombatTrackerEngine.hudVisible) return
        if (client.currentScreen is WowDpsInspectorScreen) return

        // Real-time mouse drag & resize tracking when Chat (`T`) or another cursor screen is open
        if (draggingHud || resizingHud) {
            val handle = client.window.handle
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                val sw = client.window.scaledWidth
                val sh = client.window.scaledHeight
                val rw = client.window.width.coerceAtLeast(1)
                val rh = client.window.height.coerceAtLeast(1)
                val mx = client.mouse.x * sw.toDouble() / rw.toDouble()
                val my = client.mouse.y * sh.toDouble() / rh.toDouble()
                handleHudMouseDragged(mx, my, sw, sh)
            } else {
                handleHudMouseReleased()
            }
        }

        // Show the golden resize grip `◢` whenever a cursor screen (like Chat `T`) is open!
        val cursorUnlocked = client.currentScreen != null
        lastRenderedHudHeight = renderMeterWindow(
            context = context,
            x = CombatTrackerEngine.hudX,
            y = CombatTrackerEngine.hudY,
            width = CombatTrackerEngine.hudWidth,
            height = CombatTrackerEngine.hudHeight,
            showResizeGrip = cursorUnlocked
        )
    }

    /**
     * Renders the small DPS window at `(x, y)` with exact unscaled `width` and `height`,
     * dynamically adapting all bars, columns, fonts, and sub-panels to fit!
     */
    fun renderMeterWindow(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int = CombatTrackerEngine.hudHeight,
        showResizeGrip: Boolean
    ): Int {
        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val segment = CombatTrackerEngine.getActiveSegment()
        val mode = CombatTrackerEngine.currentMode
        val filter = CombatTrackerEngine.groupFilterMode
        val sortedCombatants = segment.getSortedCombatants(mode, filter)
        val sortedTargets = segment.getSortedSegmentTargets()
        val scale = CombatTrackerEngine.uiScale

        val openKey = WowDpsClient.getOpenGuiKeyLabel()
        val hideKey = WowDpsClient.getToggleHudKeyLabel()

        // Responsive typography & row geometry that automatically adapts to window width & height
        val adaptiveTextScale = when {
            width < 155 || height < 62 -> 0.72f
            width > 270 && height > 145 -> 0.85f
            else -> 0.79f
        }
        val headerH = if (height < 62) 12 else HEADER_HEIGHT
        val footerH = if (height < 62) 10 else FOOTER_HEIGHT

        val matrices = context.matrices
        matrices.push()
        matrices.translate(x.toFloat(), y.toFloat(), 0f)
        matrices.scale(scale, scale, 1f)

        // Collapsed state (Header only)
        if (CombatTrackerEngine.hudCollapsed) {
            context.fill(-1, -1, width + 1, headerH + 1, 0xCC101216.toInt())
            val headerColorTop = if (segment.inCombat) 0xE64A1818.toInt() else 0xE6222834.toInt()
            val headerColorBottom = if (segment.inCombat) 0xE62D1010.toInt() else 0xE6161A22.toInt()
            context.fillGradient(0, 0, width, headerH, headerColorTop, headerColorBottom)
            context.fill(0, headerH - 1, width, headerH, 0xAA8C7440.toInt())

            val controls = "[${filter.shortName}][+][X]"
            val rightW = getScaledTextWidth(textRenderer, controls, adaptiveTextScale)
            drawScaledText(context, textRenderer, controls, width - rightW - 3, 3, 0xFF93C5FD.toInt(), adaptiveTextScale)

            val durationStr = CombatTrackerEngine.formatDuration(segment.durationSeconds)
            val rawTitle = "${mode.shortName} [$durationStr]"
            val safeTitle = fitTextToPixels(textRenderer, rawTitle, width - rightW - 10, adaptiveTextScale)
            drawScaledText(context, textRenderer, safeTitle, 5, 3, 0xFFFFE6A3.toInt(), adaptiveTextScale)

            matrices.pop()
            return (headerH * scale).toInt()
        }

        val totalHeight = height.coerceIn(48, 380)
        val bodyHeight = (totalHeight - headerH - footerH).coerceAtLeast(18)

        // 1. Outer frame & dark translucent backdrop sized to the exact user-chosen `width x totalHeight`
        context.fill(-1, -1, width + 1, totalHeight + 1, 0xCC0D1015.toInt())
        context.fill(0, 0, width, totalHeight, 0xC4141820.toInt())

        // 2. Top Header Bar (with Mode, Timer, Party Filter pill `[Все/Пати/Я]`, Segment, and `[-][X]`)
        val headerColorTop = if (segment.inCombat) 0xE64A1818.toInt() else 0xE6222834.toInt()
        val headerColorBottom = if (segment.inCombat) 0xE62D1010.toInt() else 0xE6161A22.toInt()
        context.fillGradient(0, 0, width, headerH, headerColorTop, headerColorBottom)
        context.fill(0, headerH - 1, width, headerH, 0xAA8C7440.toInt())

        val statusDotColor = if (segment.inCombat) 0xFFFF3B3B.toInt() else 0xFF6B7280.toInt()
        context.fill(3, (headerH - 4) / 2, 7, (headerH - 4) / 2 + 4, statusDotColor)

        val partyStatus = PartyTeamIntegration.getClientPartyStatus(client.player)
        val filterBadge = when {
            width < 165 -> "[${filter.shortName}][-][X]"
            else -> "[${filter.shortName}] ${CombatTrackerEngine.selectedSegmentView.label(CombatTrackerEngine)} [-][X]"
        }
        val segBadgeW = getScaledTextWidth(textRenderer, filterBadge, adaptiveTextScale)
        val badgeColor = if (filter == GroupFilterMode.PARTY_ONLY) 0xFF6EE7B7.toInt() else 0xFFCBD5E1.toInt()
        drawScaledText(context, textRenderer, filterBadge, width - segBadgeW - 3, 3, badgeColor, adaptiveTextScale)

        val durationStr = CombatTrackerEngine.formatDuration(segment.durationSeconds)
        val partyTag = if (partyStatus.shortBadge.isNotEmpty() && width >= 195) " • ${partyStatus.shortBadge}" else ""
        val rawHeaderLeft = "${mode.shortName}$partyTag [$durationStr]"
        val maxHeaderLeftPx = (width - segBadgeW - 14).coerceAtLeast(20)
        val safeHeaderLeft = fitTextToPixels(textRenderer, rawHeaderLeft, maxHeaderLeftPx, adaptiveTextScale)
        drawScaledText(context, textRenderer, safeHeaderLeft, 9, 3, 0xFFFFE6A3.toInt(), adaptiveTextScale)

        // 3. Adaptive Body Layout
        val durationSec = segment.durationSeconds
        val itemCount = if (mode == MeterMode.TARGETS) sortedTargets.size else sortedCombatants.size

        // Adaptive bar height: expands slightly when window is tall, compacts when window is short
        val adaptiveBarH = when {
            bodyHeight < 34 -> 11
            bodyHeight > 130 && itemCount in 1..3 -> 15
            else -> BAR_HEIGHT
        }
        val rowStep = adaptiveBarH + BAR_GAP
        val maxPossibleRows = max(1, (bodyHeight - 2) / rowStep)

        var rowY = headerH + 2

        if (itemCount == 0) {
            val emptyLine1 = fitTextToPixels(
                textRenderer,
                WowDpsI18n.tr("wowdps.hud.waiting", filter.displayName),
                width - 10,
                adaptiveTextScale
            )
            drawScaledText(context, textRenderer, emptyLine1, 5, rowY + 2, 0xFF94A3B8.toInt(), adaptiveTextScale)
            if (bodyHeight >= 26) {
                val partyHint = if (partyStatus.shortBadge.isNotEmpty()) {
                    WowDpsI18n.tr("wowdps.hud.party_status", partyStatus.activeSystem, partyStatus.partyName)
                } else {
                    WowDpsI18n.tr("wowdps.hud.party_hint", openKey)
                }
                val emptyLine2 = fitTextToPixels(textRenderer, partyHint, width - 10, adaptiveTextScale)
                drawScaledText(context, textRenderer, emptyLine2, 5, rowY + 13, 0xFF64748B.toInt(), adaptiveTextScale)
            }
        } else if (mode == MeterMode.TARGETS) {
            val totalDmgAllTargets = max(0.0001, sortedTargets.sumOf { it.totalDamage })
            val topTargetDmg = max(0.0001, sortedTargets.firstOrNull()?.totalDamage ?: 1.0)
            val limit = min(sortedTargets.size, maxPossibleRows)

            for (i in 0 until limit) {
                val t = sortedTargets[i]
                val pctOfTop = (t.totalDamage / topTargetDmg).coerceIn(0.02, 1.0)
                val pctOfTotal = (t.totalDamage / totalDmgAllTargets) * 100.0

                context.fill(2, rowY, width - 2, rowY + adaptiveBarH, 0x99101318.toInt())
                val fillW = ((width - 4) * pctOfTop).toInt()
                val sliceColor = CombatTrackerEngine.CHART_SLICE_COLORS[i % CombatTrackerEngine.CHART_SLICE_COLORS.size] and 0xFFFFFF
                val barTop = (0xD8 shl 24) or sliceColor
                val barBot = (0xD8 shl 24) or darkenRgb(sliceColor, 0.65f)
                context.fillGradient(2, rowY, 2 + fillW, rowY + adaptiveBarH, barTop, barBot)

                val iconScale = (adaptiveBarH / 19.0f).coerceIn(0.55f, 0.78f)
                drawTargetMobIcon(context, t.targetEntityTypeId, t.targetIconItemId, 3, rowY + 1, iconScale)

                val totStr = CombatTrackerEngine.formatCompactNumber(t.totalDamage)
                val dpsStr = CombatTrackerEngine.formatCompactNumber(t.totalDamage / max(1.0, durationSec))
                val rightStat = when {
                    width < 160 -> "$totStr ($dpsStr/с)"
                    else -> "$totStr ($dpsStr/с, ${String.format("%.0f%%", pctOfTotal)})"
                }
                val rightStatW = getScaledTextWidth(textRenderer, rightStat, adaptiveTextScale)
                val textOffsetY = max(1, (adaptiveBarH - 7) / 2)
                drawScaledText(context, textRenderer, rightStat, width - rightStatW - 4, rowY + textOffsetY, 0xFFF8FAFC.toInt(), adaptiveTextScale)

                val rawLeft = "${i + 1}. ${t.targetName} (${t.hits})"
                val maxLeftPx = (width - rightStatW - 22).coerceAtLeast(16)
                val safeLeft = fitTextToPixels(textRenderer, rawLeft, maxLeftPx, adaptiveTextScale)
                drawScaledText(context, textRenderer, safeLeft, 16, rowY + textOffsetY, 0xFFFFFFFF.toInt(), adaptiveTextScale)

                rowY += rowStep
            }
        } else {
            val groupTotal = max(0.0001, segment.getTotalGroupMetric(mode, filter))
            val topValue = max(0.0001, sortedCombatants.firstOrNull()?.getMetricValue(mode, durationSec) ?: 1.0)
            val limit = min(sortedCombatants.size, maxPossibleRows)

            for (i in 0 until limit) {
                val c = sortedCombatants[i]
                val metricVal = c.getMetricValue(mode, durationSec)
                val pctOfTop = (metricVal / topValue).coerceIn(0.02, 1.0)
                val pctOfGroup = (metricVal / groupTotal) * 100.0

                context.fill(2, rowY, width - 2, rowY + adaptiveBarH, 0x99101318.toInt())

                val fillW = ((width - 4) * pctOfTop).toInt()
                val baseRgb = c.classColorRgb and 0xFFFFFF
                val barTop = (0xD8 shl 24) or baseRgb
                val barBottom = (0xD8 shl 24) or darkenRgb(baseRgb, 0.65f)
                context.fillGradient(2, rowY, 2 + fillW, rowY + adaptiveBarH, barTop, barBottom)

                // Highlight teammate / self bar top border
                val topEdgeColor = when {
                    c.isLocalPlayer() -> 0x88FDE047.toInt()
                    c.isTeammateOrSelf() -> 0x8834D399.toInt()
                    else -> (0x44 shl 24) or 0xFFFFFF
                }
                context.fill(2, rowY, 2 + fillW, rowY + 1, topEdgeColor)

                val iconScale = (adaptiveBarH / 19.0f).coerceIn(0.55f, 0.78f)
                drawAbilityOrSpellIcon(
                    context = context,
                    iconItemId = c.lastIconItemId,
                    spellTextureId = c.lastSpellTextureId,
                    magicSchool = c.lastMagicSchool,
                    hasGlint = c.lastIconHasGlint,
                    x = 3,
                    y = rowY + 1,
                    scale = iconScale
                )

                // Responsive Right Column (adapts to narrow / medium / wide `width`)
                val rightStat = when (mode) {
                    MeterMode.DPS -> {
                        val tot = CombatTrackerEngine.formatCompactNumber(c.totalDamageDealt)
                        val dps = CombatTrackerEngine.formatCompactNumber(c.getDps(durationSec))
                        when {
                            width < 158 -> WowDpsI18n.tr("wowdps.hud.per_sec", dps)
                            width >= 255 -> "$tot ($dps/с | Кр ${String.format("%.0f%%", c.critPercent)} | ${String.format("%.0f%%", pctOfGroup)})"
                            else -> "$tot ($dps/с, ${String.format("%.0f%%", pctOfGroup)})"
                        }
                    }
                    MeterMode.TOTAL_DAMAGE, MeterMode.TARGETS -> {
                        val tot = CombatTrackerEngine.formatCompactNumber(c.totalDamageDealt)
                        "$tot (${String.format("%.0f%%", pctOfGroup)})"
                    }
                    MeterMode.BURST_DPS -> {
                        val burst = CombatTrackerEngine.formatCompactNumber(c.peakBurstDps)
                        "${WowDpsI18n.tr("wowdps.hud.per_sec", burst)} (${WowDpsI18n.tr("wowdps.hud.crits_suffix", String.format("%.0f%%", c.critPercent))})"
                    }
                    MeterMode.DAMAGE_TAKEN -> {
                        val taken = CombatTrackerEngine.formatCompactNumber(c.totalDamageTaken)
                        "$taken (${String.format("%.0f%%", pctOfGroup)})"
                    }
                }
                val rightStatW = getScaledTextWidth(textRenderer, rightStat, adaptiveTextScale)
                val textOffsetY = max(1, (adaptiveBarH - 7) / 2)
                drawScaledText(context, textRenderer, rightStat, width - rightStatW - 4, rowY + textOffsetY, 0xFFF8FAFC.toInt(), adaptiveTextScale)

                // Responsive Left Column with FTB Teams / Open Parties badge (`★` or `🤝[FTB]` / `🤝[OPAC]`)
                val badge = c.getPartyBadge()
                val rolePrefix = when {
                    badge == "★" -> "★"
                    badge.isNotEmpty() && width >= 220 -> "🤝[$badge] "
                    badge.isNotEmpty() -> "🤝"
                    else -> ""
                }
                val weaponPart = if (width >= 245 && c.lastSourceWeaponName.isNotEmpty() && !c.lastAbilityName.contains(c.lastSourceWeaponName)) {
                    "${c.lastSourceWeaponName}➔"
                } else {
                    ""
                }
                val targetSuffix = if (width >= 195 && c.lastTargetName.isNotEmpty()) "→${c.lastTargetName}" else ""
                val rawLeft = when {
                    width < 155 -> "${i + 1}. $rolePrefix${c.name}"
                    else -> "${i + 1}. $rolePrefix${c.name} [$weaponPart${c.lastAbilityName}$targetSuffix]"
                }
                val maxLeftPx = (width - rightStatW - 22).coerceAtLeast(16)
                val safeLeft = fitTextToPixels(textRenderer, rawLeft, maxLeftPx, adaptiveTextScale)
                val nameColor = if (c.isTeammateOrSelf() && !c.isLocalPlayer()) 0xFFA7F3D0.toInt() else 0xFFFFFFFF.toInt()
                drawScaledText(context, textRenderer, safeLeft, 16, rowY + textOffsetY, nameColor, adaptiveTextScale)

                rowY += rowStep
            }

            // =========================================================================================
            // AUTO-ADAPTING EXTRA SECTIONS WHEN SMALL HUD WINDOW IS STRETCHED TALLER (`hudHeight`)!
            // =========================================================================================
            val footerTopY = totalHeight - footerH
            val focusCombatant = sortedCombatants.firstOrNull { it.uuid == selectedHudCombatantUuid }
                ?: sortedCombatants.firstOrNull { it.isLocalPlayer() }
                ?: sortedCombatants.firstOrNull()

            if (focusCombatant != null && footerTopY - rowY >= 26) {
                // Sub-panel A: Live Top Spells & Source Weapon Breakdown inside the small HUD window!
                context.fill(2, rowY, width - 2, rowY + 1, 0x668C7440)
                rowY += 2

                val subHeaderRaw = WowDpsI18n.tr("wowdps.hud.sub_spells", focusCombatant.name)
                val subHeaderSafe = fitTextToPixels(textRenderer, subHeaderRaw, width - 8, adaptiveTextScale * 0.94f)
                drawScaledText(context, textRenderer, subHeaderSafe, 4, rowY + 1, 0xFFFFE6A3.toInt(), adaptiveTextScale * 0.94f)
                rowY += 10

                val topSpells = focusCombatant.getSortedAbilities()
                val totalPlayerDmg = max(0.0001, focusCombatant.totalDamageDealt)
                val reserveForMiniGraph = if (footerTopY - rowY >= 64) 36 else 0
                val spellRowH = 11

                var sIdx = 0
                while (sIdx < topSpells.size && (rowY + spellRowH) <= (footerTopY - reserveForMiniGraph)) {
                    val ab = topSpells[sIdx]
                    val pct = (ab.totalDamage / totalPlayerDmg) * 100.0
                    val barW = ((width - 6) * (pct / 100.0)).toInt()

                    context.fill(3, rowY, width - 3, rowY + spellRowH, 0x800E121A.toInt())
                    val schoolCol = getMagicSchoolColor(ab.magicSchool) and 0xFFFFFF
                    context.fillGradient(
                        3,
                        rowY,
                        3 + barW,
                        rowY + spellRowH,
                        (0x88 shl 24) or schoolCol,
                        (0x50 shl 24) or schoolCol
                    )

                    drawAbilityOrSpellIcon(
                        context = context,
                        iconItemId = ab.iconItemId,
                        spellTextureId = ab.spellTextureId,
                        magicSchool = ab.magicSchool,
                        hasGlint = ab.iconHasGlint,
                        x = 4,
                        y = rowY,
                        scale = 0.58f
                    )

                    val rightSpellTxt = "${CombatTrackerEngine.formatCompactNumber(ab.totalDamage)} (${String.format("%.0f%%", pct)})"
                    val rightSpellW = getScaledTextWidth(textRenderer, rightSpellTxt, adaptiveTextScale * 0.92f)
                    drawScaledText(context, textRenderer, rightSpellTxt, width - rightSpellW - 5, rowY + 2, 0xFF86EFAC.toInt(), adaptiveTextScale * 0.92f)

                    val wpnFrom = if (width >= 200 && ab.sourceWeaponName.isNotEmpty()) " [${ab.sourceWeaponName}]" else ""
                    val leftSpellRaw = "${ab.abilityName}$wpnFrom"
                    val leftSpellSafe = fitTextToPixels(textRenderer, leftSpellRaw, (width - rightSpellW - 22).coerceAtLeast(14), adaptiveTextScale * 0.92f)
                    drawScaledText(context, textRenderer, leftSpellSafe, 15, rowY + 2, 0xFFE2E8F0.toInt(), adaptiveTextScale * 0.92f)

                    rowY += spellRowH + 1
                    sIdx++
                }

                // Sub-panel B: Live Mini DPS Graph inside the small HUD window when stretched tall!
                val remainingGraphH = footerTopY - rowY - 2
                if (remainingGraphH >= 24) {
                    val gx = 4
                    val gy = rowY + 1
                    val gw = width - 8
                    val gh = remainingGraphH

                    context.fill(gx - 1, gy - 1, gx + gw + 1, gy + gh + 1, 0xAA0B0E14.toInt())
                    context.fill(gx, gy, gx + gw, gy + gh, 0xCC0F141D.toInt())

                    val series = focusCombatant.getTimelineSeries(24)
                    val maxVal = max(1.0, series.maxOfOrNull { max(it.damageInSecond, it.cumulativeDps) } ?: 10.0)
                    val count = max(1, series.size)
                    val stepW = gw.toFloat() / count.toFloat()

                    for (bIdx in series.indices) {
                        val pt = series[bIdx]
                        val bx1 = (gx + bIdx * stepW).toInt()
                        val bx2 = max(bx1 + 1, (gx + (bIdx + 1) * stepW - 1).toInt())
                        val barH = ((pt.damageInSecond / maxVal).coerceIn(0.0, 1.0) * (gh - 3)).toInt()
                        if (barH > 0) {
                            val col = if (pt.critHitsInSecond > 0) 0xCCF59E0B.toInt() else 0xAA3B82F6.toInt()
                            context.fill(bx1, gy + gh - barH, bx2, gy + gh, col)
                        }
                    }

                    val peakStr = WowDpsI18n.tr("wowdps.hud.peak_dps", CombatTrackerEngine.formatCompactNumber(focusCombatant.peakBurstDps))
                    val safePeak = fitTextToPixels(textRenderer, peakStr, gw - 6, adaptiveTextScale * 0.88f)
                    drawScaledText(context, textRenderer, safePeak, gx + 3, gy + 2, 0xFF93C5FD.toInt(), adaptiveTextScale * 0.88f)
                }
            }
        }

        // 4. Footer status bar with Modded Mob Portrait + Target / Party DPS + Resize Grip `◢`
        val footerY = totalHeight - footerH
        context.fill(0, footerY, width, totalHeight, 0xE610131A.toInt())
        context.fill(0, footerY, width, footerY + 1, 0x66475569)

        drawTargetMobIcon(context, segment.primaryTargetEntityTypeId, segment.primaryTargetIconId, 3, footerY + 1, 0.58f)

        val rightHint = when {
            showResizeGrip -> WowDpsI18n.tr("wowdps.hud.drag_hint")
            width < 165 -> "[$openKey]|[$hideKey]"
            else -> WowDpsI18n.tr("wowdps.hud.report_hint", openKey, hideKey)
        }
        val rightHintW = getScaledTextWidth(textRenderer, rightHint, adaptiveTextScale)
        val gripOffset = if (showResizeGrip) 9 else 3
        val hintColor = if (showResizeGrip) 0xFFFDE047.toInt() else 0xFF94A3B8.toInt()
        drawScaledText(context, textRenderer, rightHint, width - rightHintW - gripOffset, footerY + 2, hintColor, adaptiveTextScale)

        val partyDpsStr = CombatTrackerEngine.formatCompactNumber(segment.getTotalGroupMetric(MeterMode.DPS, filter))
        val rawTargetInfo = if (width >= 210 && sortedCombatants.size > 1) {
            WowDpsI18n.tr("wowdps.hud.target_party_label", segment.primaryTargetName, partyDpsStr)
        } else {
            WowDpsI18n.tr("wowdps.hud.target_label", segment.primaryTargetName)
        }
        val maxFooterLeftPx = (width - rightHintW - gripOffset - 17).coerceAtLeast(14)
        val safeTargetInfo = fitTextToPixels(textRenderer, rawTargetInfo, maxFooterLeftPx, adaptiveTextScale)
        drawScaledText(context, textRenderer, safeTargetInfo, 14, footerY + 2, 0xFF6EE7B7.toInt(), adaptiveTextScale)

        if (showResizeGrip) {
            drawCornerResizeGrip(context, width, totalHeight)
        }

        matrices.pop()
        return (totalHeight * scale).toInt()
    }

    /**
     * Handles mouse click on the small HUD window (works from BOTH `WowDpsInspectorScreen` AND `ChatScreen`!).
     * Returns true if the click was consumed by the small HUD window.
     */
    fun handleHudMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!CombatTrackerEngine.hudVisible) return false
        val scale = CombatTrackerEngine.uiScale
        val hx = CombatTrackerEngine.hudX
        val hy = CombatTrackerEngine.hudY
        val hwScaled = (CombatTrackerEngine.hudWidth * scale).toInt()
        val hhScaled = if (CombatTrackerEngine.hudCollapsed) {
            (HEADER_HEIGHT * scale).toInt()
        } else {
            (CombatTrackerEngine.hudHeight * scale).toInt()
        }
        val headerScaled = (HEADER_HEIGHT * scale).toInt()

        // 1. Check bottom-right corner resize grip `◢` (or right/bottom border within 8px)
        if (!CombatTrackerEngine.hudCollapsed) {
            val inCornerGrip = mouseX >= hx + hwScaled - 14 && mouseX <= hx + hwScaled + 5 &&
                mouseY >= hy + hhScaled - 14 && mouseY <= hy + hhScaled + 5
            val onRightEdge = mouseX >= hx + hwScaled - 5 && mouseX <= hx + hwScaled + 4 &&
                mouseY >= hy + headerScaled && mouseY <= hy + hhScaled
            val onBottomEdge = mouseY >= hy + hhScaled - 5 && mouseY <= hy + hhScaled + 4 &&
                mouseX >= hx && mouseX <= hx + hwScaled

            if (inCornerGrip || onRightEdge || onBottomEdge) {
                resizingHud = true
                return true
            }
        }

        // 2. Check Top Header Bar clicks (`[X]`, `[-]/[+]`, `[Все/Пати/Я]`, Mode cycle, or Drag)
        if (mouseY >= hy && mouseY <= hy + headerScaled && mouseX >= hx && mouseX <= hx + hwScaled) {
            val relXFromRight = (hx + hwScaled) - mouseX
            // [X] button
            if (relXFromRight <= 15 * scale) {
                CombatTrackerEngine.hudVisible = false
                return true
            }
            // [-]/[+] collapse button
            if (relXFromRight > 15 * scale && relXFromRight <= 30 * scale) {
                CombatTrackerEngine.hudCollapsed = !CombatTrackerEngine.hudCollapsed
                return true
            }
            // Right-click on header cycles Group Filter (Все -> Пати -> Я)
            if (button == 1) {
                CombatTrackerEngine.groupFilterMode = CombatTrackerEngine.groupFilterMode.next()
                return true
            }
            // Clicking the `[Все/Пати/Я]` filter badge area on the header
            if (relXFromRight > 30 * scale && relXFromRight <= 88 * scale) {
                CombatTrackerEngine.groupFilterMode = CombatTrackerEngine.groupFilterMode.next()
                return true
            }
            // Dragging the header
            draggingHud = true
            hudDragOffsetX = (mouseX - hx).toInt()
            hudDragOffsetY = (mouseY - hy).toInt()
            return true
        }

        // 3. Clicking a combatant bar inside the small HUD selects that player/teammate
        if (!CombatTrackerEngine.hudCollapsed &&
            mouseX >= hx && mouseX <= hx + hwScaled &&
            mouseY > hy + headerScaled && mouseY <= hy + hhScaled
        ) {
            if (button == 1) {
                // Right-click inside HUD body cycles MeterMode (УВС -> Урон -> Цели -> Бурст -> Получ.)
                CombatTrackerEngine.currentMode = CombatTrackerEngine.currentMode.next()
                return true
            }
            val localY = ((mouseY - hy) / scale).toInt() - HEADER_HEIGHT - 2
            val rowIndex = localY / (BAR_HEIGHT + BAR_GAP)
            val sorted = CombatTrackerEngine.getActiveSegment().getSortedCombatants(
                CombatTrackerEngine.currentMode,
                CombatTrackerEngine.groupFilterMode
            )
            if (rowIndex in sorted.indices) {
                selectedHudCombatantUuid = sorted[rowIndex].uuid
                return true
            }
        }

        return false
    }

    /**
     * Handles mouse drag for resizing (`hudWidth` & `hudHeight` pixel-by-pixel!) or moving the small HUD window.
     */
    fun handleHudMouseDragged(mouseX: Double, mouseY: Double, screenWidth: Int, screenHeight: Int): Boolean {
        val scale = CombatTrackerEngine.uiScale
        if (resizingHud) {
            val newUnscaledW = ((mouseX - CombatTrackerEngine.hudX) / scale).toInt()
            val newUnscaledH = ((mouseY - CombatTrackerEngine.hudY) / scale).toInt()
            CombatTrackerEngine.hudWidth = newUnscaledW.coerceIn(125, 460)
            CombatTrackerEngine.hudHeight = newUnscaledH.coerceIn(48, 380)
            return true
        }
        if (draggingHud) {
            val hwScaled = (CombatTrackerEngine.hudWidth * scale).toInt()
            val hhScaled = (CombatTrackerEngine.hudHeight * scale).toInt()
            CombatTrackerEngine.hudX = (mouseX.toInt() - hudDragOffsetX).coerceIn(2, max(4, screenWidth - hwScaled - 2))
            CombatTrackerEngine.hudY = (mouseY.toInt() - hudDragOffsetY).coerceIn(2, max(4, screenHeight - hhScaled - 2))
            return true
        }
        return false
    }

    fun handleHudMouseReleased(): Boolean {
        val wasActive = draggingHud || resizingHud
        draggingHud = false
        resizingHud = false
        return wasActive
    }

    /**
     * Renders a Spell Engine spell texture icon (if present in resource packs), OR an ItemStack icon
     * enhanced with a glowing WoW-style Elemental Magic School badge for spells & magic!
     */
    fun drawAbilityOrSpellIcon(
        context: DrawContext,
        iconItemId: String,
        spellTextureId: String,
        magicSchool: String,
        hasGlint: Boolean,
        x: Int,
        y: Int,
        scale: Float
    ) {
        val boxSize = (16 * scale).toInt().coerceAtLeast(9)

        if (magicSchool != "physical") {
            val schoolColor = getMagicSchoolColor(magicSchool)
            context.fill(x, y, x + boxSize, y + boxSize, (0x66 shl 24) or (schoolColor and 0xFFFFFF))
            context.fill(x, y, x + boxSize, y + 1, (0xCC shl 24) or (schoolColor and 0xFFFFFF))
            context.fill(x, y + boxSize - 1, x + boxSize, y + boxSize, (0xCC shl 24) or (schoolColor and 0xFFFFFF))
        }

        val validSpellTex = resolveExistingSpellTexture(spellTextureId, magicSchool)
        if (validSpellTex != null) {
            val matrices = context.matrices
            matrices.push()
            matrices.translate(x.toFloat(), y.toFloat(), 0f)
            matrices.scale(scale, scale, 1f)
            context.drawTexture(validSpellTex, 0, 0, 0f, 0f, 16, 16, 16, 16)
            matrices.pop()
            return
        }

        val stack = CombatTrackerEngine.getIconStack(iconItemId, hasGlint)
        drawScaledItemIcon(context, stack, x, y, scale)

        if (magicSchool != "physical") {
            val badgeCol = (0xFF shl 24) or (getMagicSchoolColor(magicSchool) and 0xFFFFFF)
            context.fill(x + boxSize - 4, y + boxSize - 4, x + boxSize, y + boxSize, 0xFF090D16.toInt())
            context.fill(x + boxSize - 3, y + boxSize - 3, x + boxSize, y + boxSize, badgeCol)
        }
    }

    /**
     * Renders the icon of ANY Vanilla or Modded Mob/Boss/Dummy.
     */
    fun drawTargetMobIcon(
        context: DrawContext,
        entityTypeId: String,
        fallbackItemId: String,
        x: Int,
        y: Int,
        scale: Float
    ) {
        val boxSize = (16 * scale).toInt().coerceAtLeast(9)

        val isGenericFallback = fallbackItemId == "minecraft:skeleton_skull" || fallbackItemId == "minecraft:nether_star"
        val isModdedEntity = entityTypeId.isNotEmpty() && !entityTypeId.startsWith("minecraft:") && !entityTypeId.contains("dummy")

        if (isModdedEntity || isGenericFallback) {
            val previewEntity = CombatTrackerEngine.getOrCreatePreviewEntity(entityTypeId)
            if (previewEntity != null) {
                try {
                    context.fill(x, y, x + boxSize, y + boxSize, 0xAA0F172A.toInt())
                    context.fill(x, y, x + boxSize, y + 1, 0x8864748B.toInt())
                    val maxDim = max(0.8f, max(previewEntity.height, previewEntity.width))
                    val modelScale = ((boxSize * 0.72f) / maxDim).toInt().coerceIn(2, 9)
                    InventoryScreen.drawEntity(
                        context,
                        x + boxSize / 2,
                        y + boxSize - 1,
                        modelScale,
                        25f,
                        10f,
                        previewEntity
                    )
                    return
                } catch (_: Exception) {
                }
            }
        }

        val stack = CombatTrackerEngine.getIconStack(fallbackItemId, false)
        drawScaledItemIcon(context, stack, x, y, scale)
    }

    private fun resolveExistingSpellTexture(spellTextureId: String, magicSchool: String): Identifier? {
        val rm = MinecraftClient.getInstance().resourceManager
        if (spellTextureId.isNotEmpty()) {
            val cached = textureExistsCache.getOrPut(spellTextureId) {
                val id = Identifier.tryParse(spellTextureId)
                if (id != null && rm.getResource(id).isPresent) id else null
            }
            if (cached != null) return cached
        }
        if (magicSchool != "physical") {
            val schoolTexKey = "spell_power:textures/mob_effect/$magicSchool.png"
            val cachedSchool = textureExistsCache.getOrPut(schoolTexKey) {
                val id = Identifier.tryParse(schoolTexKey)
                if (id != null && rm.getResource(id).isPresent) id else null
            }
            if (cachedSchool != null) return cachedSchool
        }
        return null
    }

    fun getMagicSchoolColor(school: String): Int = when (school) {
        "fire" -> 0xFF6B2B      // Fiery Orange-Red
        "frost" -> 0x38BDF8     // Ice Cyan-Blue
        "arcane" -> 0xD946EF    // Arcane Magenta-Violet
        "lightning" -> 0xFACC15 // Electric Yellow
        "soul" -> 0x2DD4BF      // Soul Teal-Turquoise
        "holy" -> 0xFDE047      // Holy Radiant Gold
        "blood" -> 0xEF4444     // Crimson Blood
        "void" -> 0x8B5CF6      // Deep Void Purple
        "nature" -> 0x22C55E    // Nature Emerald
        else -> 0x94A3B8
    }

    fun drawCornerResizeGrip(context: DrawContext, w: Int, h: Int) {
        context.fill(w - 8, h - 2, w - 1, h - 1, 0xFFFBBF24.toInt())
        context.fill(w - 2, h - 8, w - 1, h - 1, 0xFFFBBF24.toInt())
        context.fill(w - 5, h - 5, w - 3, h - 3, 0xFFFBBF24.toInt())
    }

    fun drawScaledItemIcon(context: DrawContext, stack: ItemStack, x: Int, y: Int, scale: Float) {
        val matrices = context.matrices
        matrices.push()
        matrices.translate(x.toFloat(), y.toFloat(), 0f)
        matrices.scale(scale, scale, 1f)
        context.drawItem(stack, 0, 0)
        matrices.pop()
    }

    fun drawScaledText(
        context: DrawContext,
        textRenderer: TextRenderer,
        text: String,
        x: Int,
        y: Int,
        color: Int,
        scale: Float
    ) {
        val matrices = context.matrices
        matrices.push()
        matrices.translate(x.toFloat(), y.toFloat(), 0f)
        matrices.scale(scale, scale, 1f)
        context.drawTextWithShadow(textRenderer, text, 0, 0, color)
        matrices.pop()
    }

    fun getScaledTextWidth(textRenderer: TextRenderer, text: String, scale: Float): Int {
        return (textRenderer.getWidth(text) * scale).toInt()
    }

    fun fitTextToPixels(textRenderer: TextRenderer, text: String, maxWidthPx: Int, scale: Float): String {
        if (maxWidthPx <= 6) return ""
        if (getScaledTextWidth(textRenderer, text, scale) <= maxWidthPx) return text
        val ellipsis = ".."
        val ellipsisW = getScaledTextWidth(textRenderer, ellipsis, scale)
        var len = text.length - 1
        while (len > 1) {
            val candidate = text.substring(0, len)
            if (getScaledTextWidth(textRenderer, candidate, scale) + ellipsisW <= maxWidthPx) {
                return candidate + ellipsis
            }
            len--
        }
        return text.take(1)
    }

    private fun darkenRgb(rgb: Int, factor: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((rgb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((rgb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
