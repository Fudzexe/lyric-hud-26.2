package dev.lyricshud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches time-synced (.lrc) lyrics from LRCLIB (https://lrclib.net) - free, no account or API key. */
public final class LyricsFetcher {
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(6))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d+):(\\d{1,2}(?:[.:]\\d{1,3})?)]");

	private LyricsFetcher() {}

	public static List<MediaState.Line> fetch(String title, String artist, long durationSec) {
		try {
			String mainArtist = artist.split("[,;&]")[0].trim();
			String cleanTitle = cleanTitle(title);

			// 1. exact match (best: uses duration to pick the right version)
			if (durationSec > 0) {
				String synced = syncedFromGet(title, mainArtist, durationSec);
				if (synced == null && !cleanTitle.equals(title)) synced = syncedFromGet(cleanTitle, mainArtist, durationSec);
				if (synced != null) return parse(synced);
			}

			// 2. search fallbacks
			String synced = firstSynced(get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(mainArtist)), durationSec);
			if (synced == null) synced = firstSynced(get("https://lrclib.net/api/search?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(mainArtist)), durationSec);
			if (synced == null) synced = firstSynced(get("https://lrclib.net/api/search?q=" + enc(cleanTitle + " " + mainArtist)), durationSec);
			if (synced != null) return parse(synced);
		} catch (Exception e) {
			LyricsHudClient.LOGGER.warn("Lyrics lookup failed for '{} - {}': {}", artist, title, e.toString());
		}
		return List.of();
	}

	/** "The Days - NOTION Remix (feat. X)" -> "The Days" */
	static String cleanTitle(String t) {
		String s = t.replaceAll("\\s*[(\\[].*?[)\\]]", "");
		int dash = s.indexOf(" - ");
		if (dash > 0) s = s.substring(0, dash);
		return s.trim();
	}

	private static String syncedFromGet(String track, String artist, long durationSec) throws Exception {
		String url = "https://lrclib.net/api/get?track_name=" + enc(track) + "&artist_name=" + enc(artist) + "&duration=" + durationSec;
		String body = get(url);
		if (body == null) return null;
		JsonObject o = JsonParser.parseString(body).getAsJsonObject();
		return str(o, "syncedLyrics");
	}

	private static String firstSynced(String body, long durationSec) {
		if (body == null) return null;
		JsonElement root = JsonParser.parseString(body);
		if (!root.isJsonArray()) return null;
		JsonArray arr = root.getAsJsonArray();
		String best = null;
		double bestDiff = Double.MAX_VALUE;
		for (JsonElement e : arr) {
			JsonObject o = e.getAsJsonObject();
			String synced = str(o, "syncedLyrics");
			if (synced == null) continue;
			double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
			double diff = durationSec > 0 ? Math.abs(d - durationSec) : 0;
			if (diff < bestDiff) {
				bestDiff = diff;
				best = synced;
			}
		}
		return best;
	}

	private static String str(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) return null;
		String s = o.get(key).getAsString();
		return s.isBlank() ? null : s;
	}

	private static String get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "LyricsHud-FabricMod/1.0 (personal Minecraft mod)")
			.GET()
			.build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return res.statusCode() == 200 ? res.body() : null;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** Parses "[01:23.45] some words" lines (multiple timestamps per line allowed). */
	static List<MediaState.Line> parse(String lrc) {
		List<MediaState.Line> out = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = TIMESTAMP.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				long min = Long.parseLong(m.group(1));
				double sec = Double.parseDouble(m.group(2).replace(':', '.'));
				times.add(min * 60_000L + Math.round(sec * 1000));
				end = m.end();
			}
			if (times.isEmpty()) continue;
			String text = raw.substring(end).trim();
			for (long t : times) out.add(new MediaState.Line(t, text));
		}
		out.sort(Comparator.comparingLong(MediaState.Line::timeMs));
		return out;
	}
}
