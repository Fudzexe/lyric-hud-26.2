package dev.lyricshud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Press the editor key (default J): drag the lyrics box anywhere, tweak colours/size, Esc to save.
 * Dragging is done by polling the mouse button every frame, which keeps this independent of the
 * mouse-event signatures that keep changing between Minecraft versions.
 */
public class LyricsEditScreen extends Screen {
	/** True while the editor is open, so the normal HUD copy of the lyrics hides itself. */
	public static volatile boolean open = false;

	private record Preset(String name, String hex) {}

	private static final Preset[] PRESETS = {
		new Preset("Pink", "#FF2DA0"), new Preset("Purple", "#B57BFF"), new Preset("Cyan", "#3DDCFF"),
		new Preset("Green", "#1ED760"), new Preset("Orange", "#FF9F1A"), new Preset("Red", "#FF4D4D"),
		new Preset("White", "#FFFFFF"), new Preset("Yellow", "#FFE14D")
	};

	private final LyricsConfig cfg;
	private final MediaState state;

	private LyricsRenderer.Box box;
	private boolean dragging;
	private boolean wasDown;
	private int grabX, grabY;
	private int presetIndex = -1;

	private EditBox activeField;
	private EditBox upcomingField;

	public LyricsEditScreen(LyricsConfig cfg, MediaState state) {
		super(Component.literal("Lyrics HUD"));
		this.cfg = cfg;
		this.state = state;
	}

	@Override
	protected void init() {
		open = true;

		int gap = 4;
		int colW = Math.min(150, (width - 16 - gap * 2) / 3);
		int totalW = colW * 3 + gap * 2;
		int left = (width - totalW) / 2;
		int top = height - (24 * 6) - 2;

		// row 0: colours
		activeField = colourField(left, top, colW, "Active line #RRGGBB", cfg.activeColor, true);
		upcomingField = colourField(left + colW + gap, top, colW, "Other lines #RRGGBB", cfg.upcomingColor, false);
		addRenderableWidget(activeField);
		addRenderableWidget(upcomingField);
		addRenderableWidget(Button.builder(Component.literal("Colour preset"), b -> {
			presetIndex = (presetIndex + 1) % PRESETS.length;
			cfg.activeColor = PRESETS[presetIndex].hex();
			activeField.setValue(cfg.activeColor);
			b.setMessage(Component.literal("Preset: " + PRESETS[presetIndex].name()));
		}).bounds(left + (colW + gap) * 2, top, colW, 20).build());

		// row 1: sliders
		int r1 = top + 24;
		addRenderableWidget(new Slider(left, r1, colW, "Scale", 0.5, 3.0, cfg.scale, v -> cfg.scale = (float) (Math.round(v * 20) / 20.0), v -> String.format("%.2fx", Math.round(v * 20) / 20.0)));
		addRenderableWidget(new Slider(left + colW + gap, r1, colW, "Width", 120, 420, cfg.width, v -> cfg.width = (int) Math.round(v / 5) * 5, v -> (int) Math.round(v / 5) * 5 + "px"));
		addRenderableWidget(new Slider(left + (colW + gap) * 2, r1, colW, "Background", 0, 100, cfg.backgroundOpacity, v -> cfg.backgroundOpacity = (int) Math.round(v), v -> Math.round(v) + "%"));

		// row 2: toggles
		int r2 = top + 48;
		addRenderableWidget(toggle(left, r2, colW, "Song header", () -> cfg.showHeader, v -> cfg.showHeader = v));
		addRenderableWidget(toggle(left + colW + gap, r2, colW, "Text shadow", () -> cfg.shadow, v -> cfg.shadow = v));
		addRenderableWidget(toggle(left + (colW + gap) * 2, r2, colW, "Snap to grid", () -> cfg.snapToGrid, v -> cfg.snapToGrid = v));

		// row 3: line counts + timing
		int r3 = top + 72;
		addRenderableWidget(Button.builder(Component.literal("Lines after: " + cfg.linesAfter), b -> {
			cfg.linesAfter = cfg.linesAfter >= 5 ? 0 : cfg.linesAfter + 1;
			b.setMessage(Component.literal("Lines after: " + cfg.linesAfter));
		}).bounds(left, r3, colW, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Lines before: " + cfg.linesBefore), b -> {
			cfg.linesBefore = cfg.linesBefore >= 3 ? 0 : cfg.linesBefore + 1;
			b.setMessage(Component.literal("Lines before: " + cfg.linesBefore));
		}).bounds(left + colW + gap, r3, colW, 20).build());
		int half = (colW - gap) / 2;
		int tx = left + (colW + gap) * 2;
		addRenderableWidget(Button.builder(Component.literal("Earlier"), b -> cfg.offsetMs += 50)
			.bounds(tx, r3, half, 20)
			.tooltip(Tooltip.create(Component.literal("Lyrics appear 50ms earlier")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("Later"), b -> cfg.offsetMs -= 50)
			.bounds(tx + half + gap, r3, half, 20)
			.tooltip(Tooltip.create(Component.literal("Lyrics appear 50ms later")))
			.build());

		// row 4: sliders (art size, wave strength, title scroll speed)
		int r4 = top + 96;
		addRenderableWidget(new Slider(left, r4, colW, "Art size", 24, 64, cfg.artSize, v -> cfg.artSize = (int) Math.round(v), v -> Math.round(v) + "px"));
		addRenderableWidget(new Slider(left + colW + gap, r4, colW, "Wave", 0, 6, cfg.waveStrength, v -> cfg.waveStrength = (int) Math.round(v), v -> Math.round(v) == 0 ? "OFF" : Math.round(v) + "px"));
		addRenderableWidget(new Slider(left + (colW + gap) * 2, r4, colW, "Title scroll", 0, 20, cfg.scrollSpeed, v -> cfg.scrollSpeed = (int) Math.round(v), v -> Math.round(v) == 0 ? "OFF" : Math.round(v) + "/s"));

		// row 5: album art + wave toggles
		int r5 = top + 120;
		addRenderableWidget(toggle(left, r5, colW, "Album art", () -> cfg.showArt, v -> cfg.showArt = v));
		addRenderableWidget(Button.builder(Component.literal("Art side: " + (cfg.artRight ? "Right" : "Left")), b -> {
			cfg.artRight = !cfg.artRight;
			b.setMessage(Component.literal("Art side: " + (cfg.artRight ? "Right" : "Left")));
		}).bounds(left + colW + gap, r5, colW, 20).build());
		addRenderableWidget(toggle(left + (colW + gap) * 2, r5, colW, "Lyric wave", () -> cfg.waveEnabled, v -> cfg.waveEnabled = v));

		// top bar
		addRenderableWidget(toggle(4, 4, 100, "HUD", () -> cfg.enabled, v -> cfg.enabled = v));
		addRenderableWidget(Button.builder(Component.literal("Reset position"), b -> {
			cfg.x = 0.02f;
			cfg.y = 0.30f;
		}).bounds(width - 104, 4, 100, 20).build());
	}

	private EditBox colourField(int x, int y, int w, String hint, String value, boolean active) {
		EditBox f = new EditBox(font, x, y, w, 20, Component.literal(hint));
		f.setMaxLength(7);
		f.setHint(Component.literal(hint));
		f.setTooltip(Tooltip.create(Component.literal("Hex colour, e.g. #FF2DA0")));
		f.setValue(value);
		f.setResponder(s -> {
			Integer rgb = LyricsConfig.tryParseHex(s);
			f.setTextColor(rgb == null ? 0xFFFF5555 : 0xFFFFFFFF);
			if (rgb == null) return;
			String norm = String.format("#%06X", rgb);
			if (active) cfg.activeColor = norm;
			else cfg.upcomingColor = norm;
		});
		return f;
	}

	private Button toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
		return Button.builder(toggleText(name, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(toggleText(name, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build();
	}

	private static Component toggleText(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"));
	}

	// ---- drawing / dragging ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// drag handling (polls the mouse button, so no mouse-event signatures needed)
		long window = GLFW.glfwGetCurrentContext();
		boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (down && !wasDown && box != null && box.contains(mouseX, mouseY) && !overWidget(mouseX, mouseY)) {
			dragging = true;
			grabX = mouseX - box.x();
			grabY = mouseY - box.y();
		}
		if (!down) dragging = false;
		wasDown = down;

		if (dragging && box != null) {
			int nx = mouseX - grabX;
			int ny = mouseY - grabY;
			if (cfg.snapToGrid) {
				nx = Math.round(nx / 10f) * 10;
				ny = Math.round(ny / 10f) * 10;
			}
			nx = Math.max(0, Math.min(Math.max(0, width - box.w()), nx));
			ny = Math.max(0, Math.min(Math.max(0, height - box.h()), ny));
			cfg.x = nx / (float) width;
			cfg.y = ny / (float) height;
		}

		super.extractRenderState(g, mouseX, mouseY, delta); // background + widgets

		// the lyrics preview, drawn on top so it stays crisp
		MediaState.Snapshot snap = state.snapshot();
		if (!snap.hasMedia()) snap = LyricsRenderer.preview();
		box = LyricsRenderer.draw(g, cfg, snap, width, height);
		outline(g, box.x() - 2, box.y() - 2, box.w() + 4, box.h() + 4, dragging ? 0xFFFFFFFF : 0x88FFFFFF);

		// colour swatches inside the hex fields
		swatch(g, activeField, cfg.activeRgb());
		swatch(g, upcomingField, cfg.upcomingRgb());

		String hint = "Drag the lyrics to move them  -  Esc to save  -  timing offset: " + (cfg.offsetMs >= 0 ? "+" : "") + cfg.offsetMs + "ms";
		g.text(font, hint, (width - font.width(hint)) / 2, 30, 0xFFFFFFFF, true);
	}

	private void swatch(GuiGraphicsExtractor g, EditBox f, int rgb) {
		int x2 = f.getX() + f.getWidth() - 4;
		g.fill(x2 - 12, f.getY() + 4, x2, f.getY() + 16, 0xFF000000 | rgb);
		outline(g, x2 - 12, f.getY() + 4, 12, 12, 0xFFFFFFFF);
	}

	private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	private boolean overWidget(double mx, double my) {
		for (GuiEventListener e : children()) {
			if (e.isMouseOver(mx, my)) return true;
		}
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		open = false;
		cfg.save();
	}

	/** Slider that maps 0..1 onto [min,max]. */
	private static class Slider extends AbstractSliderButton {
		private final String label;
		private final double min, max;
		private final DoubleConsumer apply;
		private final DoubleFunction<String> fmt;

		Slider(int x, int y, int w, String label, double min, double max, double current, DoubleConsumer apply, DoubleFunction<String> fmt) {
			super(x, y, w, 20, Component.empty(), (current - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.apply = apply;
			this.fmt = fmt;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + fmt.apply(min + (max - min) * value)));
		}

		@Override
		protected void applyValue() {
			apply.accept(min + (max - min) * value);
		}
	}
}
