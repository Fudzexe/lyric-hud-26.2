package dev.lyricshud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Draws the lyrics panel. Shared by the in-game HUD and the drag/colour editor screen. */
public final class LyricsRenderer {
	public record Box(int x, int y, int w, int h) {
		public boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Row(Component text, int argb, int x, int y) {}

	/** One wrapped piece of the active line, drawn letter by letter so it can ripple. */
	private record WaveRow(String text, int y, int charStart) {}

	private static final int PAD = 6;
	private static final int LINE_H = 9;
	private static final int GAP = 3;
	private static final double WAVE_SIGMA = 3.0; // how many letters wide the wave is

	private static final int DIM_TEXT = 0x8A8A94;

	private LyricsRenderer() {}

	/** Fake song used by the editor when nothing is actually playing. */
	public static MediaState.Snapshot preview() {
		List<MediaState.Line> lines = List.of(
			new MediaState.Line(0, "Do you think about the days when we sat down"),
			new MediaState.Line(4000, "Smoking, wine and drinking haze? Or"),
			new MediaState.Line(8000, "Was it the other way?"),
			new MediaState.Line(12000, "Drag me anywhere you like"),
			new MediaState.Line(16000, "Change my colours below"));
		long pos = System.currentTimeMillis() % 20000L;
		return new MediaState.Snapshot(true, true, "Preview Song", "Lyrics HUD", pos, 20000, lines, MediaState.LyricsStatus.READY, "preview");
	}

	private static Component plainText(String s) {
		return Component.literal(s);
	}

	private static Component boldText(String s) {
		return Component.literal(s).withStyle(st -> st.withBold(true));
	}

	/** Returns the on-screen box that was drawn (in GUI pixels). */
	public static Box draw(GuiGraphicsExtractor ctx, LyricsConfig cfg, MediaState.Snapshot s, int screenW, int screenH) {
		Font tr = Minecraft.getInstance().font;
		ArtManager.tick(s.artToken(), s.title(), s.artist());

		int w = cfg.width;
		int innerW = Math.max(40, w - PAD * 2);
		int activeRgb = cfg.activeRgb();
		int upcomingRgb = cfg.upcomingRgb();

		List<Row> rows = new ArrayList<>();
		List<WaveRow> waveRows = new ArrayList<>();
		int y = PAD;
		int barY = -1;

		// ---- album art placement ----
		boolean art = cfg.showHeader && cfg.showArt;
		int artSize = cfg.artSize;
		int artX = PAD;
		int textX = PAD;
		int textW = innerW;
		if (art) {
			textW = Math.max(30, innerW - artSize - 6);
			if (cfg.artRight) artX = w - PAD - artSize;
			else textX = PAD + artSize + 6;
		}

		// ---- header ----
		if (cfg.showHeader) {
			boolean hasArtist = !s.artist().isEmpty();
			int textBlockH = (LINE_H + 2) + (LINE_H + 1) + (hasArtist ? LINE_H + 1 : 0);
			int ty = PAD;
			if (art && artSize > textBlockH) ty = PAD + (artSize - textBlockH) / 2;

			rows.add(new Row(plainText(s.playing() ? "NOW PLAYING" : "PAUSED"), argb(1f, DIM_TEXT), textX, ty));
			ty += LINE_H + 2;
			rows.add(new Row(boldText(marquee(tr, s.title(), textW, true, cfg.scrollSpeed)), argb(1f, 0xFFFFFF), textX, ty));
			ty += LINE_H + 1;
			if (hasArtist) {
				rows.add(new Row(plainText(marquee(tr, s.artist(), textW, false, cfg.scrollSpeed)), argb(1f, DIM_TEXT), textX, ty));
			}

			int headerEnd = Math.max(PAD + textBlockH, art ? PAD + artSize : 0);
			barY = headerEnd + 2;
			y = headerEnd + 2 + 2 + 6;
		}

		// ---- lyric lines ----
		double waveProgress = 0;
		int waveTotal = 0;
		switch (s.lyricsStatus()) {
			case LOADING -> y = addWrapped(rows, tr, "Loading lyrics...", innerW, y, argb(1f, DIM_TEXT), false);
			case NOT_FOUND -> y = addWrapped(rows, tr, "No synced lyrics found", innerW, y, argb(1f, DIM_TEXT), false);
			case READY -> {
				List<MediaState.Line> lines = s.lines();
				long pos = s.posMs() + cfg.offsetMs;
				int idx = -1;
				for (int i = 0; i < lines.size(); i++) {
					if (lines.get(i).timeMs() <= pos) idx = i;
					else break;
				}
				int from = Math.max(0, idx - cfg.linesBefore);
				int to = Math.min(lines.size() - 1, idx + cfg.linesAfter);
				for (int i = from; i <= to; i++) {
					int dist = i - idx;
					String text = lines.get(i).text().isEmpty() ? "♪" : lines.get(i).text();
					if (dist == 0 && cfg.waveEnabled && cfg.waveStrength > 0) {
						// how far through the line we are, so the wave can follow the singing
						waveTotal = text.codePointCount(0, text.length());
						MediaState.Line cur = lines.get(i);
						long next = i + 1 < lines.size() ? lines.get(i + 1).timeMs() : cur.timeMs() + 4000L;
						long dur = Math.min(Math.max(1L, next - cur.timeMs()), Math.max(1500L, waveTotal * 130L));
						waveProgress = Math.max(0.0, Math.min(1.0, (pos - cur.timeMs()) / (double) dur));
						y = addWaveLines(waveRows, tr, text, innerW, y);
					} else {
						int color = dist == 0 ? argb(1f, activeRgb) : argb(fade(dist), upcomingRgb);
						y = addWrapped(rows, tr, text, innerW, y, color, dist == 0);
					}
				}
			}
		}

		int h = y - GAP + PAD;
		float scale = cfg.scale;
		int bw = Math.round(w * scale);
		int bh = Math.round(h * scale);
		int x0 = clamp(Math.round(cfg.x * screenW), 0, Math.max(0, screenW - bw));
		int y0 = clamp(Math.round(cfg.y * screenH), 0, Math.max(0, screenH - bh));

		ctx.pose().pushMatrix();
		ctx.pose().translate(x0, y0);
		ctx.pose().scale(scale, scale);

		if (cfg.background) {
			int bg = ((cfg.backgroundOpacity * 255 / 100) << 24) | 0x12121A;
			ctx.fill(1, 0, w - 1, h, bg); // cheap rounded corners
			ctx.fill(0, 1, w, h - 1, bg);
		}

		if (art) drawArt(ctx, tr, artX, PAD, artSize);

		for (Row r : rows) {
			ctx.text(tr, r.text(), r.x(), r.y(), r.argb(), cfg.shadow);
		}

		// ---- the wave: a ripple that travels across the active line as it's sung ----
		if (!waveRows.isEmpty()) {
			double center = -2 * WAVE_SIGMA + waveProgress * (waveTotal + 4 * WAVE_SIGMA);
			for (WaveRow wr : waveRows) {
				int cx = PAD;
				int index = wr.charStart();
				String str = wr.text();
				for (int off = 0; off < str.length(); ) {
					int cp = str.codePointAt(off);
					off += Character.charCount(cp);
					Component glyph = boldText(new String(Character.toChars(cp)));
					double d = index - center;
					double env = Math.exp(-(d * d) / (2 * WAVE_SIGMA * WAVE_SIGMA));
					int dy = (int) Math.round(-cfg.waveStrength * env * Math.cos(d * 0.9));
					int color = lerpRgb(activeRgb, 0xFFFFFF, (float) (env * 0.45));
					ctx.text(tr, glyph, cx, wr.y() + dy, argb(1f, color), cfg.shadow);
					cx += tr.width(glyph);
					index++;
				}
			}
		}

		if (barY >= 0) {
			ctx.fill(PAD, barY, PAD + innerW, barY + 2, 0x44FFFFFF);
			if (s.durMs() > 0) {
				int filled = (int) (innerW * Math.min(1.0, (double) s.posMs() / s.durMs()));
				ctx.fill(PAD, barY, PAD + filled, barY + 2, argb(1f, activeRgb));
			}
		}

		ctx.pose().popMatrix();
		return new Box(x0, y0, bw, bh);
	}

	private static void drawArt(GuiGraphicsExtractor ctx, Font tr, int x, int y, int size) {
		ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xAA000000); // thin dark frame
		Identifier id = ArtManager.textureId();
		if (id != null) {
			int tw = ArtManager.width();
			int th = ArtManager.height();
			ctx.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, tw, th, tw, th);
		} else {
			ctx.fill(x, y, x + size, y + size, 0xFF2A2A33);
			String note = "♪";
			ctx.text(tr, note, x + (size - tr.width(note)) / 2, y + (size - LINE_H) / 2, 0xFF8A8A94, false);
		}
	}

	private static int addWrapped(List<Row> rows, Font tr, String text, int width, int y, int argb, boolean bold) {
		for (String piece : wrapPlain(tr, text, width, bold)) {
			rows.add(new Row(bold ? boldText(piece) : plainText(piece), argb, PAD, y));
			y += LINE_H;
		}
		return y + GAP;
	}

	private static int addWaveLines(List<WaveRow> out, Font tr, String text, int width, int y) {
		int start = 0;
		for (String piece : wrapPlain(tr, text, width, true)) {
			out.add(new WaveRow(piece, y, start));
			start += piece.codePointCount(0, piece.length());
			y += LINE_H;
		}
		return y + GAP;
	}

	/** Simple word wrap on plain strings (the wave needs the actual text of each line). */
	private static List<String> wrapPlain(Font tr, String text, int width, boolean bold) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = cur.length() == 0 ? word : cur + " " + word;
			if (textWidth(tr, candidate, bold) <= width) {
				cur = new StringBuilder(candidate);
				continue;
			}
			if (cur.length() > 0) {
				out.add(cur.toString());
				cur = new StringBuilder();
			}
			if (textWidth(tr, word, bold) <= width) {
				cur.append(word);
				continue;
			}
			// single word wider than the box: break it up
			for (int cp : word.codePoints().toArray()) {
				String ch = new String(Character.toChars(cp));
				if (cur.length() > 0 && textWidth(tr, cur + ch, bold) > width) {
					out.add(cur.toString());
					cur = new StringBuilder();
				}
				cur.append(ch);
			}
		}
		if (cur.length() > 0) out.add(cur.toString());
		if (out.isEmpty()) out.add("");
		return out;
	}

	// ---- scrolling text for names that are too long ----
	private static final long SCROLL_PAUSE_MS = 1800; // rest at the start of each loop

	private static int textWidth(Font tr, String s, boolean bold) {
		return bold ? tr.width(boldText(s)) : tr.width(s);
	}

	/** Returns the text unchanged if it fits, otherwise a window of it that slides along over time. */
	private static String marquee(Font tr, String text, int maxW, boolean bold, int charsPerSec) {
		if (textWidth(tr, text, bold) <= maxW) return text;
		long stepMs = charsPerSec <= 0 ? 0 : Math.max(20, 1000L / charsPerSec);

		String loop = text + "   -   ";
		long cycleMs = loop.length() * Math.max(1, stepMs);
		long m = System.currentTimeMillis() % (cycleMs + SCROLL_PAUSE_MS);
		int start = (stepMs == 0 || m < SCROLL_PAUSE_MS) ? 0 : (int) ((m - SCROLL_PAUSE_MS) / stepMs) % loop.length();
		if (start > 0 && Character.isLowSurrogate(loop.charAt(start))) start++;

		String src = (loop + loop).substring(start);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < src.length(); i++) {
			out.append(src.charAt(i));
			if (textWidth(tr, out.toString(), bold) > maxW) {
				out.setLength(out.length() - 1);
				break;
			}
		}
		return out.toString();
	}

	private static float fade(int dist) {
		if (dist < 0) return dist == -1 ? 0.5f : 0.3f;
		return switch (dist) {
			case 1 -> 0.95f;
			case 2 -> 0.55f;
			default -> 0.35f;
		};
	}

	private static int lerpRgb(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int argb(float alpha, int rgb) {
		int a = Math.max(5, Math.min(255, Math.round(alpha * 255)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
