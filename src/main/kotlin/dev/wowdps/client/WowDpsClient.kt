package dev.wowdps.client

import dev.wowdps.network.CombatDamageEvent
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

object WowDpsClient : ClientModInitializer {

    private lateinit var openGuiKey: KeyBinding
    private lateinit var toggleHudKey: KeyBinding
    private lateinit var resetMeterKey: KeyBinding

    fun getOpenGuiKeyLabel(): String {
        if (!::openGuiKey.isInitialized) return "K"
        return openGuiKey.boundKeyLocalizedText.string.uppercase()
    }

    fun getToggleHudKeyLabel(): String {
        if (!::toggleHudKey.isInitialized) return "H"
        return toggleHudKey.boundKeyLocalizedText.string.uppercase()
    }

    fun getResetKeyLabel(): String {
        if (!::resetMeterKey.isInitialized) return "ALT+R"
        return "ALT+" + resetMeterKey.boundKeyLocalizedText.string.uppercase()
    }

    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CombatDamageEvent.PACKET_ID) { client, _, buf, _ ->
            val event = CombatDamageEvent.readFromBuf(buf)
            client.execute {
                CombatTrackerEngine.onServerDamagePacket(event)
            }
        }

        HudRenderCallback.EVENT.register(WowDpsHudRenderer)

        // Allow direct dragging & corner-resizing (`◢`) of the small HUD window whenever Chat (`T`) is open!
        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            if (screen is ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register { _, mouseX, mouseY, button ->
                    !WowDpsHudRenderer.handleHudMouseClicked(mouseX, mouseY, button)
                }
                ScreenMouseEvents.allowMouseRelease(screen).register { _, _, _, _ ->
                    !WowDpsHudRenderer.handleHudMouseReleased()
                }
            }
        }

        openGuiKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.wowdps.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.categories.wowdps"
            )
        )

        toggleHudKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.wowdps.toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "key.categories.wowdps"
            )
        )

        resetMeterKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.wowdps.reset_data",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "key.categories.wowdps"
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            CombatTrackerEngine.tickClient(client)

            while (openGuiKey.wasPressed()) {
                client.setScreen(WowDpsInspectorScreen())
            }
            while (toggleHudKey.wasPressed()) {
                CombatTrackerEngine.hudVisible = !CombatTrackerEngine.hudVisible
                val keyName = getToggleHudKeyLabel()
                val statusMsg = if (CombatTrackerEngine.hudVisible) {
                    Text.translatable("wowdps.hud.shown")
                } else {
                    Text.translatable("wowdps.hud.hidden", keyName)
                }
                client.player?.sendMessage(statusMsg, true)
            }
            while (resetMeterKey.wasPressed()) {
                if (InputUtil.isKeyPressed(client.window.handle, GLFW.GLFW_KEY_LEFT_ALT)) {
                    CombatTrackerEngine.resetAll()
                    client.player?.sendMessage(Text.translatable("wowdps.hud.reset"), true)
                }
            }
        }
    }
}
