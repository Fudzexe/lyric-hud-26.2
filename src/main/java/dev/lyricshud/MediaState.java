package dev.lyricshud;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thread-safe holder for "what's playing right now" + its lyrics. Written by background threads, read by the HUD. */
public final class MediaState {
	public record Line(long timeMs, String text) {}

	public enum LyricsStatus { LOADING, READY, NOT_FOUND }

	public record Snapshot(boolean hasMedia, boolean playing, String title, String artist,
						   long posMs, long durMs, List<Line> lines, LyricsStatus lyricsStatus, String artToken) {}

	private static final long STALE_NANOS = 3_000_000_000L;

	private final ExecutorService fetchPool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "lyricshud-fetch");
		t.setDaemon(true);
		return t;
	});

	private String title = "";
	private String artist = "";
	private boolean playing;
	private long basePosMs;
	private long baseNanos;
	private long durMs;
	private long lastUpdateNanos;
	private boolean hasMedia;

	private String trackKey = "";
	private List<Line> lines = List.of();
	private LyricsStatus lyricsStatus = LyricsStatus.LOADING;
	private String artToken = "";

	/** Changes whenever the album cover for the current song changes ("" = no cover). */
	public synchronized void setArtToken(String token) {
		this.artToken = token == null ? "" : token;
	}

	public synchronized void clear() {
		hasMedia = false;
		playing = false;
	}

	/** Called by the poller roughly every 250 ms. */
	public void update(boolean isPlaying, long posMs, long durationMs, String newTitle, String newArtist) {
		String key = newTitle + "\u0000" + newArtist;
		boolean changed;
		synchronized (this) {
			hasMedia = true;
			playing = isPlaying;
			basePosMs = posMs;
			baseNanos = System.nanoTime();
			lastUpdateNanos = baseNanos;
			durMs = durationMs;
			title = newTitle;
			artist = newArtist;
			changed = !key.equals(trackKey);
			if (changed) {
				trackKey = key;
				lines = List.of();
				lyricsStatus = LyricsStatus.LOADING;
			}
		}
		if (changed) {
			final long dur = durationMs;
			fetchPool.submit(() -> {
				List<Line> result = LyricsFetcher.fetch(newTitle, newArtist, dur / 1000);
				synchronized (MediaState.this) {
					if (!key.equals(trackKey)) return; // song changed while we were fetching
					lines = result;
					lyricsStatus = result.isEmpty() ? LyricsStatus.NOT_FOUND : LyricsStatus.READY;
				}
			});
		}
	}

	public synchronized Snapshot snapshot() {
		long now = System.nanoTime();
		boolean live = hasMedia && (now - lastUpdateNanos) < STALE_NANOS;
		long pos = basePosMs;
		if (playing) pos += (now - baseNanos) / 1_000_000L;
		if (durMs > 0 && pos > durMs) pos = durMs;
		return new Snapshot(live, playing, title, artist, pos, durMs, lines, lyricsStatus, artToken);
	}
}
