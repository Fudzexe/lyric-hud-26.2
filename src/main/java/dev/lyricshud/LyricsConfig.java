package dev.lyricshud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to .minecraft/config/lyricshud.json. Colours are "#RRGGBB" strings so they're easy to hand-edit. */
public class LyricsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lyricshud.json");

	public boolean enabled = true;
	public boolean showHeader = true;
	public boolean shadow = true;
	public boolean background = true;
	public boolean snapToGrid = false;

	/** Top-left corner as a fraction of the screen, so it survives GUI scale / resolution changes. */
	public float x = 0.02f;
	public float y = 0.30f;

	public float scale = 1.0f;
	/** Panel width in GUI pixels (before scale). */
	public int width = 220;
	public int backgroundOpacity = 70; // 0-100

	public String activeColor = "#FF2DA0";
	public String upcomingColor = "#FFFFFF";

	public int linesBefore = 0;
	public int linesAfter = 2;

	/** Long title/artist scrolling, in characters per second. 0 = don't scroll. */
	public int scrollSpeed = 7;

	/** Album cover next to the song title. */
	public boolean showArt = true;
	public boolean artRight = false;
	/** Cover size in GUI pixels (before scale). */
	public int artSize = 40;

	/** Ripple that sweeps across the current lyric line as it's sung. */
	public boolean waveEnabled = true;
	/** Wave height in pixels. 0 = off. */
	public int waveStrength = 2;

	/** Nudge lyric timing, in ms. Positive = lyrics appear earlier. */
	public int offsetMs = 0;

	/** Linux only: playerctl player name (e.g. "spotify"). Empty = whichever playerctl picks. */
	public String linuxPlayer = "";

	public int activeRgb() {
		return parseHex(activeColor, 0xFF2DA0);
	}

	public int upcomingRgb() {
		return parseHex(upcomingColor, 0xFFFFFF);
	}

	/** Accepts "RRGGBB" or "#RRGGBB". Returns fallback if invalid. */
	public static int parseHex(String s, int fallback) {
		Integer v = tryParseHex(s);
		return v == null ? fallback : v;
	}

	public static Integer tryParseHex(String s) {
		if (s == null) return null;
		String t = s.trim();
		if (t.startsWith("#")) t = t.substring(1);
		if (t.length() != 6) return null;
		try {
			return Integer.parseInt(t, 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static LyricsConfig load() {
		try {
			if (Files.exists(FILE)) {
				LyricsConfig c = GSON.fromJson(Files.readString(FILE), LyricsConfig.class);
				if (c != null) return c;
			}
		} catch (Exception e) {
			LyricsHudClient.LOGGER.warn("Couldn't read lyricshud.json, using defaults", e);
		}
		LyricsConfig c = new LyricsConfig();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			LyricsHudClient.LOGGER.warn("Couldn't save lyricshud.json", e);
		}
	}
}
