package dev.lyricshud;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LyricsHudClient implements ClientModInitializer {
	public static final String MOD_ID = "lyricshud";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static LyricsConfig config;
	public static MediaState state;

	private static KeyMapping editorKey;
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		config = LyricsConfig.load();
		state = new MediaState();
		new MediaPoller(state, config).start(FabricLoader.getInstance().getConfigDir());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		editorKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.lyricshud.editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		toggleKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.lyricshud.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (editorKey.consumeClick()) {
				mc.gui.setScreen(new LyricsEditScreen(config, state));
			}
			while (toggleKey.consumeClick()) {
				config.enabled = !config.enabled;
				config.save();
			}
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "lyrics"), (graphics, deltaTracker) -> {
			if (!config.enabled || LyricsEditScreen.open) return;
			MediaState.Snapshot snap = state.snapshot();
			if (!snap.hasMedia()) return;
			LyricsRenderer.draw(graphics, config, snap, graphics.guiWidth(), graphics.guiHeight());
		});
	}
}
