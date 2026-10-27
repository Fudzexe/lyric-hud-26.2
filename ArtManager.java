package dev.lyricshud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the album cover for the current song into a Minecraft texture.
 *
 * The "art token" comes from the media helper:
 *  - Windows: a unique id; the cover was written to config/lyricshud-art.img
 *  - Linux:   the cover URL from MPRIS (file:// or https://)
 *  - Online:  if the helper gives no cover, we look it up by song + artist (iTunes, then Deezer - no API key needed)
 * Covers can be JPEG, so we convert to a small PNG with ImageIO first (Minecraft only reads PNG).
 *
 * Every failure is logged (look for "lyricshud" in latest.log) instead of silently leaving the cover blank.
 */
public final class ArtManager {
	private static final int TEX_SIZE = 128;
	private static final int LOAD_ATTEMPTS = 3;
	/** How long to wait for the OS helper's cover before falling back to an online lookup. */
	private static final long HELPER_GRACE_MS = 2500;
	private static final String ONLINE = "online:";

	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "lyricshud-art");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(6))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static volatile String requested = "";
	private static String lastSong = "";
	private static long emptySince = 0;
	private static Identifier current;
	private static int texW = 1, texH = 1;
	private static int counter = 0;

	private ArtManager() {}

	/** Null until a cover has been loaded. Render thread only. */
	public static Identifier textureId() {
		return current;
	}

	public static int width() {
		return texW;
	}

	public static int height() {
		return texH;
	}

	/** Kept so older callers still compile; prefer the 3-argument version (it enables the online lookup). */
	public static void tick(String token) {
		tick(token, "", "");
	}

	/** Call every frame from the render thread with the current art token and song. */
	public static void tick(String token, String title, String artist) {
		if (token == null) token = "";
		if (title == null) title = "";
		if (artist == null) artist = "";
		if (token.equals("preview")) return;

		String wanted;
		if (!token.isEmpty()) {
			wanted = token; // cover from the OS media helper / MPRIS
		} else if (title.isEmpty()) {
			wanted = "";
		} else {
			// No cover from the helper: give it a moment, then look the song up online.
			String song = title + "\u0000" + artist;
			long now = System.currentTimeMillis();
			if (!song.equals(lastSong)) {
				lastSong = song;
				emptySince = now;
			}
			wanted = (now - emptySince >= HELPER_GRACE_MS) ? ONLINE + song : "";
		}

		if (wanted.equals(requested)) return;
		requested = wanted;
		if (wanted.isEmpty()) {
			release();
			return;
		}

		final String tk = wanted;
		final String songTitle = title;
		final String songArtist = artist;
		if (tk.startsWith(ONLINE)) {
			LyricsHudClient.LOGGER.info("Album art: no cover from the media helper, looking up \"{}\" by \"{}\" online", songTitle, songArtist);
		} else {
			LyricsHudClient.LOGGER.info("Album art: new cover requested ({})", abbreviate(tk));
		}
		POOL.submit(() -> {
			byte[] png = null;
			Throwable failure = null;
			for (int attempt = 1; attempt <= LOAD_ATTEMPTS && png == null; attempt++) {
				try {
					png = loadPng(tk, songTitle, songArtist);
					if (png == null) {
						failure = new IOException(tk.startsWith(ONLINE) ? "no matching cover found online" : "cover was empty or the server didn't return 200");
						if (tk.startsWith(ONLINE)) break; // a definite "not found" - retrying won't change it
					}
				} catch (Throwable e) {
					failure = e;
				}
				if (png == null && attempt < LOAD_ATTEMPTS) sleep(250);
			}
			if (png == null) {
				LyricsHudClient.LOGGER.warn("Album art: couldn't load a cover: {}", String.valueOf(failure));
			}
			final byte[] data = png;
			Minecraft.getInstance().execute(() -> {
				if (!tk.equals(requested)) return; // song changed while loading
				if (data == null) {
					release();
					return;
				}
				try {
					install(data);
				} catch (Throwable e) {
					LyricsHudClient.LOGGER.warn("Album art: couldn't create the texture", e);
					release();
				}
			});
		});
	}

	private static byte[] loadPng(String token, String title, String artist) throws Exception {
		byte[] raw;
		if (token.startsWith(ONLINE)) {
			raw = lookupOnline(title, artist);
		} else if (token.startsWith("http://") || token.startsWith("https://")) {
			HttpRequest req = HttpRequest.newBuilder(URI.create(token))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "LyricsHud-FabricMod/1.2")
				.GET().build();
			HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) return null;
			raw = res.body();
		} else if (token.startsWith("file:")) {
			raw = Files.readAllBytes(Path.of(URI.create(token)));
		} else {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("lyricshud-art.img");
			raw = Files.readAllBytes(file);
		}
		if (raw == null || raw.length == 0) return null;

		BufferedImage src;
		try {
			src = ImageIO.read(new ByteArrayInputStream(raw));
		} catch (Throwable t) {
			throw new IOException("ImageIO is unavailable or failed: " + t, t);
		}
		if (src == null) {
			// Not a format ImageIO understands (e.g. WebP). Minecraft itself only reads PNG.
			if (isPng(raw)) return raw;
			throw new IOException("unsupported cover format (" + raw.length + " bytes, starts with " + hexStart(raw) + ")");
		}

		// centre-crop to a square (drawn into a fresh ARGB image so any source colour model works)
		int side = Math.min(src.getWidth(), src.getHeight());
		BufferedImage cur = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g0 = cur.createGraphics();
		g0.drawImage(src, -(src.getWidth() - side) / 2, -(src.getHeight() - side) / 2, null);
		g0.dispose();

		// halve repeatedly while still at least 2x too big (much cleaner than one huge jump), then final resize
		while (cur.getWidth() / 2 >= TEX_SIZE) {
			int n = cur.getWidth() / 2;
			cur = resize(cur, n);
		}
		cur = resize(cur, TEX_SIZE);

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(cur, "png", bos)) throw new IOException("no PNG writer available");
		return bos.toByteArray();
	}

	// ---------------------------------------------------------------------------------------------
	// Online lookup by song + artist (used when the OS helper can't provide a cover)
	// ---------------------------------------------------------------------------------------------

	/** Returns the raw cover image bytes, or null if no matching cover was found. */
	private static byte[] lookupOnline(String title, String artist) throws Exception {
		String cleanTitle = cleanTitle(title);
		String query = (artist + " " + cleanTitle).trim();
		LyricsHudClient.LOGGER.info("Album art: searching for \"{}\"", query);

		String url = null;
		try {
			url = findItunes(query, cleanTitle, artist);
		} catch (Throwable e) {
			LyricsHudClient.LOGGER.warn("Album art: iTunes lookup failed: {}", e.toString());
		}
		if (url == null) {
			try {
				url = findDeezer(query, cleanTitle, artist);
			} catch (Throwable e) {
				LyricsHudClient.LOGGER.warn("Album art: Deezer lookup failed: {}", e.toString());
			}
		}
		if (url == null) {
			LyricsHudClient.LOGGER.warn("Album art: no online match for \"{}\" by \"{}\" (browser tabs often have the channel name as artist)", title, artist);
			return null;
		}
		LyricsHudClient.LOGGER.info("Album art: downloading {}", url);
		return httpGetBytes(url);
	}

	private static String findItunes(String query, String cleanTitle, String artist) throws Exception {
		String api = "https://itunes.apple.com/search?media=music&entity=song&limit=10&term="
			+ URLEncoder.encode(query, StandardCharsets.UTF_8);
		JsonArray results = JsonParser.parseString(httpGetString(api)).getAsJsonObject().getAsJsonArray("results");
		if (results == null || results.isEmpty()) {
			LyricsHudClient.LOGGER.info("Album art: iTunes returned no results");
			return null;
		}
		for (JsonElement el : results) {
			JsonObject o = el.getAsJsonObject();
			String name = str(o, "trackName");
			String by = str(o, "artistName");
			String art = str(o, "artworkUrl100");
			if (art.isEmpty() || !matches(cleanTitle, artist, name, by)) continue;
			LyricsHudClient.LOGGER.info("Album art: iTunes match \"{}\" by \"{}\"", name, by);
			return upscaleItunes(art);
		}
		LyricsHudClient.LOGGER.info("Album art: iTunes had {} results but none matched the song/artist", results.size());
		return null;
	}

	private static String findDeezer(String query, String cleanTitle, String artist) throws Exception {
		String api = "https://api.deezer.com/search?limit=10&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		JsonArray data = JsonParser.parseString(httpGetString(api)).getAsJsonObject().getAsJsonArray("data");
		if (data == null || data.isEmpty()) {
			LyricsHudClient.LOGGER.info("Album art: Deezer returned no results");
			return null;
		}
		for (JsonElement el : data) {
			JsonObject o = el.getAsJsonObject();
			String name = str(o, "title");
			String by = o.has("artist") && o.get("artist").isJsonObject() ? str(o.getAsJsonObject("artist"), "name") : "";
			String art = "";
			if (o.has("album") && o.get("album").isJsonObject()) {
				JsonObject album = o.getAsJsonObject("album");
				art = str(album, "cover_big");
				if (art.isEmpty()) art = str(album, "cover_medium");
			}
			if (art.isEmpty() || !matches(cleanTitle, artist, name, by)) continue;
			LyricsHudClient.LOGGER.info("Album art: Deezer match \"{}\" by \"{}\"", name, by);
			return art;
		}
		LyricsHudClient.LOGGER.info("Album art: Deezer had {} results but none matched the song/artist", data.size());
		return null;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** iTunes serves any size by editing the number in the URL; 100px is too blurry. */
	static String upscaleItunes(String url) {
		return url.replace("100x100bb", "300x300bb");
	}

	/** Strips things like "(feat. X)", "[Remastered]" and " - 2011 Remaster" that stop searches matching. */
	static String cleanTitle(String title) {
		String t = title == null ? "" : title;
		t = t.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ");
		t = t.replaceAll("(?i)\\s+-\\s+(.*(remaster|version|edit|mix|live|mono|stereo|radio).*)$", "");
		t = t.replaceAll("\\s+", " ").trim();
		return t.isEmpty() ? (title == null ? "" : title.trim()) : t;
	}

	static boolean matches(String wantTitle, String wantArtist, String gotTitle, String gotArtist) {
		String wt = norm(wantTitle), gt = norm(cleanTitle(gotTitle));
		if (wt.isEmpty() || gt.isEmpty() || !(gt.contains(wt) || wt.contains(gt))) return false;
		String wa = norm(firstArtist(wantArtist));
		if (wa.isEmpty()) return true; // no artist known: title match only
		String ga = norm(gotArtist);
		return ga.contains(wa) || wa.contains(ga);
	}

	static String firstArtist(String artist) {
		if (artist == null) return "";
		return artist.split("(?i)\\s*(,|&|;|/|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*")[0].trim();
	}

	/** Lower-case letters and digits only, so punctuation/accents-free comparison is forgiving. */
	static String norm(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder();
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(c)) sb.append(c);
		}
		return sb.toString();
	}

	private static String httpGetString(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "LyricsHud-FabricMod/1.2")
			.GET().build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + URI.create(url).getHost());
		return res.body();
	}

	private static byte[] httpGetBytes(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "LyricsHud-FabricMod/1.2")
			.GET().build();
		HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200) throw new IOException("cover download returned HTTP " + res.statusCode());
		return res.body();
	}

	private static BufferedImage resize(BufferedImage in, int size) {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(in, 0, 0, size, size, null); // BufferedImage source: synchronous, unlike getScaledInstance
		g.dispose();
		return out;
	}

	private static void install(byte[] png) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
		Identifier id = Identifier.fromNamespaceAndPath(LyricsHudClient.MOD_ID, "art/" + (++counter));
		DynamicTexture tex = new DynamicTexture(() -> "lyricshud album art", img);
		mc.getTextureManager().register(id, tex);
		tex.upload();
		Identifier old = current;
		current = id;
		texW = img.getWidth();
		texH = img.getHeight();
		if (old != null) mc.getTextureManager().release(old);
		LyricsHudClient.LOGGER.info("Album art: cover ready ({}x{})", texW, texH);
	}

	private static void release() {
		if (current != null) {
			Minecraft.getInstance().getTextureManager().release(current);
			current = null;
		}
	}

	private static boolean isPng(byte[] b) {
		return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	private static String hexStart(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(8, b.length); i++) sb.append(String.format("%02X ", b[i]));
		return sb.toString().trim();
	}

	private static String abbreviate(String s) {
		return s.length() > 60 ? s.substring(0, 57) + "..." : s;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}
