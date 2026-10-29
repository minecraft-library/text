package lib.minecraft.text.font;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.text.tooling.ToolingFonts;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * The unified home for every Minecraft-style font, whether a vanilla monospace atlas, a pack colour
 * ({@code sbix}) font or the client's missing font. It is a sealed interface so a single downstream
 * surface - {@link #glyph(int)}, {@link #metrics()}, {@link #fontId()}, {@link #layout(String)} -
 * serves every kind with no caller-side type gating. Every codepoint resolves through
 * {@link #glyph(int)} into a single {@link MinecraftGlyph}, so cache, layout, and blit all speak one
 * glyph type.
 * <p>
 * Three implementations exist:
 * <ul>
 *   <li>{@link Vanilla} - the fixed six-value enum backing the vanilla {@code .otf} family
 *   (regular/bold/italic/bold-italic and the two alternate scripts). It owns everything AWT: font
 *   resolution, glyph rasterization, the render context, and the eager ASCII atlas.</li>
 *   <li>{@link Color} - an open, pack-supplied colour font keyed by {@link FontId}, backed by an
 *   {@code sbix} strike cache and a vanilla mono fallback.</li>
 *   <li>{@link Missing} - the client's missing font, drawing its missing glyph for every
 *   codepoint.</li>
 * </ul>
 * <p>
 * The vanilla and pack fonts answer under one key space: a pack font is held by a process-wide
 * registry ({@link #register}), and a vanilla constant is looked up by its id on the enum itself -
 * {@code minecraft:default}, {@code minecraft:alt} and {@code minecraft:illageralt}, the ids the
 * client names its fonts by, and {@code minecraft:default/bold}, {@code minecraft:default/italic} and
 * {@code minecraft:default/bold_italic}, the ids the library gives the default font's other members -
 * so consumers iterate {@link #fontIds()} rather than switching on the enum. {@link #get} answers the
 * font a key holds, or empty, and {@link #getOrLoad} loads a pack font where {@link #get} answers
 * none. {@link #of(FontId, Style)} answers the font a run in a font id and a style is drawn in, never
 * empty, as the client's font manager answers a font for every id: the default font's member for the
 * style's bold and italic, the font {@link #get} answers for any other id but the ids the library
 * gives the default font's members, and {@link Missing} for those and for an id the library draws no
 * font for.
 *
 * @see MinecraftGlyph
 * @see MinecraftFontMetrics
 * @see MinecraftGlyphVector
 */
public sealed interface MinecraftFont permits MinecraftFont.Vanilla, MinecraftFont.Color, MinecraftFont.Missing {

    /**
     * Load size (in AWT points) for every Minecraft font file.
     * <p>
     * {@code 16.0f} is twice the vanilla mcPixel resolution so the bitmap-derived OTFs render at the
     * {@link #MC_PIXEL_SCALE} integer factor. The cached OTFs use {@code unitsPerEm = 1024} with
     * {@code 128 units = 1 mcPixel}, so 1 em corresponds to 8 mcPixels and the load size must be an
     * integer multiple of 8 to keep every glyph on integer output pixel boundaries at AWT's fixed
     * 72 DPI.
     */
    float FONT_POINT_SIZE = 16.0f;

    /**
     * Output pixels per vanilla Minecraft pixel for the current native {@code 16.0f} load size.
     * Driven by the {@code unitsPerEm = 1024}, {@code 128 units = 1 mcPixel} layout that the
     * bitmap-to-OTF generator bakes into every font file. Callers positioning text-adjacent geometry
     * (line spacing, tooltip padding, decoration offsets) should express their measurements in terms
     * of this constant rather than hardcoding the {@code 2x} factor.
     */
    int MC_PIXEL_SCALE = 2;

    /**
     * Horizontal shear of the italic OTFs, as {@code dx} per {@code +1} unit above the baseline.
     * <p>
     * The italic slant is baked into {@code Minecraft-Italic.otf} / {@code Minecraft-BoldItalic.otf}
     * at generation time - there is no runtime glyph matrix to intercept. This constant mirrors the
     * font-generator's {@code ITALIC_SHEAR_FACTOR = 1 / ITALIC_SHEAR_VERTICAL} (with
     * {@code ITALIC_SHEAR_VERTICAL = 5}), which each glyph contour is sheared by as
     * {@code (sx + sy * factor, sy)}. Because the shear is scale-uniform it is the same slope in
     * output px, so a renderer that wants a gradient's colour bands to run parallel to italic
     * letterforms slants them by this factor. It does not affect glyph drawing.
     */
    float ITALIC_SHEAR = 1.0f / 5.0f;

    /**
     * Minecraft version used by the runtime font bootstrap when the classpath has no {@code fonts/}
     * resources. Mirrors {@link ToolingFonts#DEFAULT_VERSION} so the in-module Gradle task and the
     * runtime cache produce byte-identical output.
     */
    @NotNull String DEFAULT_VERSION = ToolingFonts.DEFAULT_VERSION;

    // --- shared surface ---

    /**
     * Returns the glyph for a codepoint, rasterizing or resolving it on first access and caching the
     * result. Never returns {@code null}: an advance-only glyph (a pack space provider, or a colour
     * row whose strike failed to decode) is modelled as a {@link MinecraftGlyphVector.Kind#SPACE}
     * sentinel. The returned glyph is the canonical cached instance ({@link MinecraftGlyph#penX() penX}
     * {@code == 0}); layout stamps each occurrence with its pen.
     *
     * @param codepoint the Unicode codepoint
     * @return the glyph
     */
    @NotNull MinecraftGlyph glyph(int codepoint);

    /**
     * Returns the font's metrics - advances and line geometry, all derived from
     * {@link MinecraftGlyph#signedAdvance()} so measurement and layout agree exactly.
     *
     * @return the font metrics
     */
    @NotNull MinecraftFontMetrics metrics();

    /**
     * Returns the font id this font registers under.
     *
     * @return the font id
     */
    @NotNull FontId fontId();

    /**
     * Lays out a run of text into a positioned glyph vector. The one layout entry point for every
     * font kind; the vector positions its pens from {@link MinecraftGlyph#signedAdvance()} with no
     * branch on the kind.
     *
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    default @NotNull MinecraftGlyphVector layout(@NotNull String text) {
        return MinecraftGlyphVector.of(this, text);
    }

    /**
     * Returns the fillable vector outline of a mono codepoint, translated by {@code penX}, for the
     * glyph-knockout hook. Fonts and glyphs with no outline ({@code sbix} strikes, space providers)
     * return empty.
     *
     * @param codepoint the Unicode codepoint
     * @param penX the pen position to translate the outline by, in output pixels
     * @return the translated outline, or empty when the glyph has no fillable outline
     */
    default @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
        return Optional.empty();
    }

    /**
     * Walks a run of text, emitting each resolved glyph at its cumulative pen and returning the total
     * signed advance. This is the single accumulation loop behind all three text surfaces:
     * {@link MinecraftGraphics#drawString drawString} drives it directly (no vector allocation on the
     * hot path), {@link MinecraftFontMetrics#stringAdvanceX metrics} sums it with a
     * {@link GlyphSink#NOOP no-op} sink, and {@link MinecraftGlyphVector#of layout} materializes a
     * vector from it. Because measure, draw, and layout share this one walk, they agree on width and
     * position by construction rather than by keeping parallel loops in step.
     * <p>
     * Pens accumulate from {@link MinecraftGlyph#signedAdvance()} as a {@code double}, so fractional
     * and negative advances (space providers) are exact; rounding happens only at blit time. The sink
     * receives the canonical cached glyph plus its pen separately - it is not handed a
     * {@link MinecraftGlyph#at stamped} copy - so the draw hot path allocates nothing per glyph.
     *
     * @param text the text to walk
     * @param sink receives each glyph and its cumulative pen X in output pixels
     * @return the total signed advance in output pixels
     */
    default double walk(@NotNull String text, @NotNull GlyphSink sink) {
        double pen = 0.0;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            MinecraftGlyph glyph = glyph(codepoint);
            sink.accept(glyph, pen);
            pen += glyph.signedAdvance();
        }
        return pen;
    }

    /**
     * Returns the codepoint obfuscated text draws in place of {@code codepoint} - a random glyph of
     * this font laid out at exactly the original's non-bold advance, chosen with at most one draw from
     * {@code random}.
     * <p>
     * U+0020 comes back unchanged with no draw. Every other codepoint takes as candidates the glyphs
     * this font lays out at exactly its non-bold advance, ordered by codepoint, and one
     * {@code random.nextInt(n)} over the {@code n} candidates picks among them, a lone candidate
     * included. A codepoint the font lacks takes the advance it lays out at, so it is substituted like
     * any other. The original comes back where the vanilla client draws its missing glyph: when no
     * glyph shares the advance, with no draw, and when the pick's advance, plain or bold, lies outside
     * {@code 0..32} mcPixels. A non-bold run therefore keeps its width on every draw.
     * <p>
     * The bold and italic {@link Vanilla} fonts choose among {@link Vanilla#REGULAR}'s glyphs at its
     * advance, as does a {@link Color} font over one of them, because the client keys bold text on its
     * non-bold advance, so a bold run's width can change from draw to draw as the client's does. A
     * {@link Color} font's candidates are its own glyphs and its fallback's, a row of its own taking
     * the place of the fallback's glyph for that codepoint. {@link Missing} holds no candidate, so
     * every codepoint comes back as itself with no draw, as the client's missing font set supports
     * none. A seeded {@link Random} draws the client's sequence of values.
     *
     * @param codepoint the codepoint being obfuscated
     * @param random the source of the draw
     * @return the codepoint to draw in its place
     */
    int obfuscate(int codepoint, @NotNull RandomGenerator random);

    /**
     * Returns {@code text} as obfuscated text draws it - each codepoint replaced through
     * {@link #obfuscate(int, RandomGenerator)}, in order, drawing from one stream.
     * <p>
     * The codepoint count and the run's non-bold advance are kept; the {@code char} length is not,
     * since a pick may be a supplementary codepoint. Obfuscate a line after it is split, never before:
     * a pick may be a codepoint a line splitter reads as a break, such as U+2028.
     *
     * @param text the text being obfuscated
     * @param random the source of the draws, at most one per codepoint
     * @return the text to draw in its place
     */
    default @NotNull String obfuscate(@NotNull String text, @NotNull RandomGenerator random) {
        StringBuilder obfuscated = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            obfuscated.appendCodePoint(obfuscate(codepoint, random));
        }
        return obfuscated.toString();
    }

    // --- awt interop surface (backs MinecraftGlyphVector's GlyphVector contract) ---

    /**
     * The backing AWT {@link Font} this font's glyph codes resolve against, and the font a
     * {@link MinecraftGlyphVector#getFont()} returns. {@link Vanilla} hands back the {@code .otf} it
     * loaded; {@link Color} lazily {@link Font#createFont createFont}s its merged {@code .ttf}
     * bytes once and shares that instance across every font id built from the same bytes;
     * {@link Missing} has none, since its glyph is a bitmap no AWT font holds.
     *
     * @return the backing AWT font
     * @throws UnsupportedOperationException for {@link Missing}, which has no backing AWT font
     */
    @NotNull Font awtFont();

    /**
     * The {@link FontRenderContext} the backing font and its glyph codes resolve under. {@link Vanilla}
     * reuses its rasterization context; {@link Color} uses an identity-transform, antialiased default
     * (it never rasterizes vector outlines - its art is {@code sbix} strikes); {@link Missing} uses an
     * identity transform with antialiasing and fractional metrics off, the context its whole-pixel box
     * is measured under.
     *
     * @return the render context
     */
    @NotNull FontRenderContext fontRenderContext();

    /**
     * Resolves a codepoint to a real glyph id in {@link #awtFont() the backing font}'s {@code cmap},
     * caching per font. This backs the {@link MinecraftGlyphVector#getGlyphCode glyph-code} surface for
     * glyphs that carry no sidecar gid - vanilla atlas glyphs and space providers; colour raster glyphs
     * report their sidecar gid directly. A codepoint the backing font lacks resolves to {@code 0}
     * ({@code .notdef}), which is the honest answer. {@link Missing} resolves every codepoint to
     * {@code 0}, since each draws its one missing glyph.
     *
     * @param codepoint the Unicode codepoint
     * @return the glyph id in the backing font
     */
    int glyphCode(int codepoint);

    /**
     * Resolves a codepoint to its glyph id through a font's {@code cmap} by laying out the single
     * character and reading the resulting glyph code. Shared by both kinds' {@link #glyphCode} caches.
     *
     * @param font the backing AWT font
     * @param frc the render context to resolve under
     * @param codepoint the Unicode codepoint
     * @return the glyph id, or {@code 0} when the font has no glyph for the codepoint
     */
    static int resolveGlyphCode(@NotNull Font font, @NotNull FontRenderContext frc, int codepoint) {
        return font.createGlyphVector(frc, new String(Character.toChars(codepoint))).getGlyphCode(0);
    }

    /**
     * Receives each glyph a {@link #walk} emits, together with its cumulative pen position. The glyph
     * is the canonical cached instance (pen {@code 0}); the pen is passed separately so the walk need
     * not allocate a {@link MinecraftGlyph#at stamped} copy on the draw hot path.
     */
    @FunctionalInterface
    interface GlyphSink {

        /**
         * A sink that discards every glyph - used by {@link MinecraftFontMetrics} to drive the walk
         * purely for its returned total advance.
         */
        @NotNull GlyphSink NOOP = (glyph, penX) -> {};

        /**
         * Accepts one walked glyph.
         *
         * @param glyph the canonical cached glyph (pen {@code 0})
         * @param penX the cumulative pen position of this occurrence, in output pixels (may be negative)
         */
        void accept(@NotNull MinecraftGlyph glyph, double penX);

    }

    // --- cache root ---

    /**
     * User-home cache root for runtime font bootstrap. {@code %LOCALAPPDATA%\minecraft-library} on
     * Windows (falling back to {@code user.home\AppData\Local\minecraft-library} when the env var is
     * unset) and {@code ~/.cache/minecraft-library} elsewhere. Durable across projects and working
     * directories so every caller of this library shares one OTF cache.
     *
     * <p>Backed by a holder class so initialization is deferred until first call, respecting the
     * interface/enum initialization order (JLS 12.4.1).
     *
     * @return the cache root path
     */
    static @NotNull Path defaultCacheRoot() {
        return DefaultsHolder.CACHE_ROOT;
    }

    // --- font registry (FontId -> MinecraftFont) ---

    /**
     * Registers a font, replacing any previous registration for its {@link #fontId()}. A font
     * registered under a {@link Vanilla} id is answered in place of the vanilla font until it is
     * unregistered.
     *
     * @param font the font to register
     * @return the registered font
     */
    static @NotNull MinecraftFont register(@NotNull MinecraftFont font) {
        Registry.MAP.put(font.fontId(), font);
        return font;
    }

    /**
     * Returns the font for a font id - the one registered under it, else the {@link Vanilla} font
     * that carries it.
     *
     * @param fontId the font id
     * @return the font, or empty where none is registered and no vanilla font carries the id
     */
    static @NotNull Optional<MinecraftFont> get(@NotNull FontId fontId) {
        return Optional.ofNullable(Registry.MAP.get(fontId)).or(() -> Vanilla.findByFontId(fontId));
    }

    /**
     * Returns the font a run in a font id and a style is drawn in, never empty, as the client's font
     * manager answers a font for every id.
     * <ul>
     *   <li><b>{@link FontId#DEFAULT}</b> - the default font's member for the style's bold and italic,
     *   read through {@link #get} under that member's id, so a font registered in a member's place
     *   answers in its stead.</li>
     *   <li><b>Any other id {@link #get} answers, bar the default font's member ids below</b> - that
     *   font, in its one face whatever the style. The alternate scripts and a pack font have no bold or
     *   italic face.</li>
     *   <li><b>An id the library draws no font for</b> - {@link Missing}, as the client draws its
     *   missing glyph for a font id it has no font under. That covers an id nothing is registered under
     *   and no vanilla font carries; an id the client has a font under that the library carries no face
     *   for - {@code minecraft:uniform}, {@code minecraft:include/default},
     *   {@code minecraft:include/space} and {@code minecraft:include/unifont}; and the ids the library
     *   gives the default font's members, {@code minecraft:default/bold},
     *   {@code minecraft:default/italic} and {@code minecraft:default/bold_italic}, which name no font of
     *   the client's. A font registered under one of those answers through {@link FontId#DEFAULT}
     *   alone.</li>
     * </ul>
     * <p>
     * A pack font answers once it is registered; unlike {@link #getOrLoad}, this loads none.
     *
     * @param fontId the font id the run names, {@link FontId#DEFAULT} where it names none
     * @param style the run's bold and italic; a script style carries neither and reads as regular
     * @return the font the run is drawn in
     */
    static @NotNull MinecraftFont of(@NotNull FontId fontId, @NotNull Style style) {
        if (fontId.equals(FontId.DEFAULT)) {
            Vanilla member = Vanilla.defaultMember(style);
            return get(member.fontId()).orElse(member);
        }

        if (Vanilla.findByFontId(fontId).filter(Vanilla::isDefaultMember).isPresent())
            return Missing.INSTANCE;

        return get(fontId).orElse(Missing.INSTANCE);
    }

    /**
     * Returns the font for a font id as {@link #get(FontId)} does, resolving and registering a pack
     * {@link Color} font via {@link Color#load(FontId)} where it answers none.
     *
     * @param fontId the font id
     * @return the font
     */
    static @NotNull MinecraftFont getOrLoad(@NotNull FontId fontId) {
        return get(fontId).orElseGet(() -> Registry.MAP.computeIfAbsent(fontId, Color::load));
    }

    /**
     * Removes a font id's registration. A {@link Vanilla} font is never registered, so this is
     * meaningful only for pack fonts and for a font registered in place of a vanilla one.
     *
     * @param fontId the font id to unregister
     */
    static void unregister(@NotNull FontId fontId) {
        Registry.MAP.remove(fontId);
    }

    /**
     * Clears every registration. Primarily for test isolation; the {@link Vanilla} fonts are not
     * registrations, so they stay available.
     */
    static void clear() {
        Registry.MAP.clear();
    }

    /**
     * Returns a snapshot of every font id {@link #get(FontId)} answers: each {@link Vanilla} font's
     * and every registered one.
     *
     * @return the font ids
     */
    static @NotNull Set<FontId> fontIds() {
        Set<FontId> fontIds = new HashSet<>(Registry.MAP.keySet());
        Vanilla.forEach(font -> fontIds.add(font.fontId()));
        return Set.copyOf(fontIds);
    }

    // --- inner types ---

    /**
     * The fixed vanilla font family plus its two alternate-script companions, each wrapping a
     * pre-loaded {@link Font} and a lazy glyph atlas for pure {@link PixelBuffer} text rendering.
     * <p>
     * At enum initialization the underlying {@code .otf} font is loaded via AWT and font-level
     * metrics (ascent, descent, height) are captured. Printable ASCII glyphs (codepoints 32-126) are
     * eagerly rasterized so the first render has zero AWT overhead; all other glyphs are lazily
     * rasterized on first use and cached. Glyph bitmaps are white-on-transparent and tinted at draw
     * time - no {@link Graphics2D} is needed after initialization.
     */
    @Getter
    @EnumLookup
    enum Vanilla implements MinecraftFont {

        REGULAR("Minecraft-Regular.otf", Style.REGULAR, "minecraft:default"),
        BOLD("Minecraft-Bold.otf", Style.BOLD, "minecraft:default/bold"),
        ITALIC("Minecraft-Italic.otf", Style.ITALIC, "minecraft:default/italic"),
        BOLD_ITALIC("Minecraft-BoldItalic.otf", Style.BOLD_ITALIC, "minecraft:default/bold_italic"),
        GALACTIC("Minecraft-Galactic.otf", Style.GALACTIC, "minecraft:alt"),
        ILLAGERALT("Minecraft-Illageralt.otf", Style.ILLAGERALT, "minecraft:illageralt");

        /**
         * First printable ASCII codepoint the font eagerly pre-caches at enum init.
         */
        private static final int EAGER_ASCII_START = 32;

        /**
         * Last printable ASCII codepoint eagerly pre-cached.
         */
        private static final int EAGER_ASCII_END = 126;

        /**
         * The lowest glyph code the JDK resolves a codepoint to when it draws that codepoint as
         * nothing - a control or a default-ignorable.
         */
        private static final int INVISIBLE_GLYPH_CODE = 0xFFFE;

        /**
         * The underlying AWT font, retained for lazy glyph rasterization.
         */
        private final @NotNull Font actual;

        /**
         * Filesystem path to the backing {@code .otf} file. Guaranteed to exist after construction.
         */
        private final @NotNull Path path;

        /**
         * The style category this enum value belongs to.
         */
        @KeyField
        private final @NotNull Style style;

        @KeyField
        @Getter(AccessLevel.NONE)
        private final @NotNull FontId fontId;

        @Getter(AccessLevel.NONE)
        private final @NotNull MinecraftFontMetrics metrics;

        /**
         * The AWT {@link FontMetrics} captured at init - reused by glyph rasterization.
         */
        private final @NotNull FontMetrics awtMetrics;

        /**
         * The AWT {@link FontRenderContext} captured at init - reused by glyph rasterization and the
         * knockout outline hook.
         */
        private final @NotNull FontRenderContext awtFrc;

        @Getter(AccessLevel.NONE)
        private final @NotNull ConcurrentMap<Integer, MinecraftGlyph> glyphCache;

        /**
         * Per-font {@code codepoint -> cmap gid} cache backing the {@link MinecraftGlyphVector}
         * glyph-code surface. Distinct from {@link #glyphCache}: that holds rasterized bitmaps, this
         * holds only integer glyph ids resolved through the AWT font's {@code cmap}.
         */
        @Getter(AccessLevel.NONE)
        private final @NotNull ConcurrentMap<Integer, Integer> gidCache;

        /**
         * This font's own glyphs grouped by advance, scanned on the first {@link #glyphsByWidth()} -
         * {@code null} until then, and always in the fonts that answer {@link #REGULAR}'s table.
         */
        @Getter(AccessLevel.NONE)
        private volatile GlyphsByWidth glyphsByWidth;

        Vanilla(@NotNull String fileName, @NotNull Style style, @NotNull String fontId) {
            Resolved resolved = resolveFont(fileName);
            this.actual = resolved.font();
            this.path = resolved.path();
            this.style = style;
            this.fontId = FontId.parse(fontId);
            this.glyphCache = Concurrent.newMap();
            this.gidCache = Concurrent.newMap();

            BufferedImage temp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = temp.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                g.setFont(this.actual);
                this.awtMetrics = g.getFontMetrics();
                this.awtFrc = g.getFontRenderContext();
            } finally {
                g.dispose();
            }

            this.metrics = new MinecraftFontMetrics(this, this.actual,
                this.awtMetrics.getAscent(), this.awtMetrics.getDescent(), this.awtMetrics.getHeight());

            // Eagerly rasterize printable ASCII so the first render has zero AWT overhead.
            for (int cp = EAGER_ASCII_START; cp <= EAGER_ASCII_END; cp++)
                this.glyphCache.put(cp, rasterizeGlyph(cp));
        }

        /**
         * Returns the vanilla font whose {@link #getStyle() style} matches the given {@link Style},
         * falling back to {@link #REGULAR} when no match exists.
         *
         * @param style the style to look up
         * @return the matching font, or {@link #REGULAR}
         */
        public static @NotNull Vanilla of(@NotNull Style style) {
            return findByStyle(style).orElse(REGULAR);
        }

        /**
         * Returns the default font's member for a style's bold and italic - {@link #REGULAR},
         * {@link #BOLD}, {@link #ITALIC} or {@link #BOLD_ITALIC}. A script style carries neither, so it
         * answers {@link #REGULAR}.
         *
         * @param style the style
         * @return the member
         */
        static @NotNull Vanilla defaultMember(@NotNull Style style) {
            return switch (style) {
                case BOLD -> Vanilla.BOLD;
                case ITALIC -> Vanilla.ITALIC;
                case BOLD_ITALIC -> Vanilla.BOLD_ITALIC;
                case REGULAR, GALACTIC, ILLAGERALT -> Vanilla.REGULAR;
            };
        }

        /**
         * Returns whether this font is one of the default font's members rather than an alternate
         * script.
         *
         * @return whether {@link #defaultMember(Style)} answers this font for its own style
         */
        boolean isDefaultMember() {
            return defaultMember(this.style) == this;
        }

        @Override
        public @NotNull MinecraftGlyph glyph(int codepoint) {
            return this.glyphCache.computeIfAbsent(codepoint, this::rasterizeGlyph);
        }

        @Override
        public @NotNull MinecraftFontMetrics metrics() {
            return this.metrics;
        }

        @Override
        public @NotNull FontId fontId() {
            return this.fontId;
        }

        @Override
        public @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
            GlyphVector vector = this.actual.createGlyphVector(this.awtFrc, new String(Character.toChars(codepoint)));
            Shape outline = vector.getGlyphOutline(0);
            return Optional.of(AffineTransform.getTranslateInstance(penX, 0).createTransformedShape(outline));
        }

        @Override
        public @NotNull Font awtFont() {
            return this.actual;
        }

        @Override
        public @NotNull FontRenderContext fontRenderContext() {
            return this.awtFrc;
        }

        @Override
        public int glyphCode(int codepoint) {
            return this.gidCache.computeIfAbsent(codepoint, cp -> resolveGlyphCode(this.actual, this.awtFrc, cp));
        }

        /** {@inheritDoc} */
        @Override
        public int obfuscate(int codepoint, @NotNull RandomGenerator random) {
            return glyphsByWidth().obfuscate(codepoint, random);
        }

        /**
         * Returns the glyphs obfuscated text in this font picks from, scanning them on first use.
         * <p>
         * The bold and italic fonts answer {@link #REGULAR}'s table, because the client draws every
         * weight and slant from one font and keys on its non-bold advance. The other fonts scan their
         * own codepoints and read each advance from the font's metrics, which rasterizes no glyph.
         *
         * @return the table
         */
        @NotNull GlyphsByWidth glyphsByWidth() {
            return switch (this) {
                case BOLD, ITALIC, BOLD_ITALIC -> REGULAR.glyphsByWidth();
                case REGULAR, GALACTIC, ILLAGERALT -> scannedGlyphsByWidth();
            };
        }

        private @NotNull GlyphsByWidth scannedGlyphsByWidth() {
            GlyphsByWidth table = this.glyphsByWidth;
            if (table == null) {
                synchronized (this) {
                    table = this.glyphsByWidth;
                    if (table == null) {
                        table = GlyphsByWidth.of(scanMembers(), this.awtMetrics::charWidth);
                        this.glyphsByWidth = table;
                    }
                }
            }
            return table;
        }

        /**
         * Returns every codepoint this font draws a visible glyph for, ascending: one the font can
         * display whose glyph code is not one the JDK reserves for drawing nothing. Each glyph code is
         * resolved afresh rather than through {@link #glyphCode}, which would cache every one.
         */
        private int @NotNull [] scanMembers() {
            return IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
                .filter(this.actual::canDisplay)
                .filter(codepoint -> resolveGlyphCode(this.actual, this.awtFrc, codepoint) < INVISIBLE_GLYPH_CODE)
                .toArray();
        }

        /**
         * Rasterizes a single glyph as white-on-transparent into a {@link PixelBuffer}. Queries the
         * AWT {@link FontMetrics} and {@link FontRenderContext} captured at init rather than spinning
         * up a throwaway scratch {@link Graphics2D} for every codepoint - only the per-glyph
         * {@link BufferedImage} (sized to the visual bounds) is newly allocated.
         */
        private @NotNull MinecraftGlyph rasterizeGlyph(int codepoint) {
            int advanceWidth = this.awtMetrics.charWidth(codepoint);
            char[] chars = Character.toChars(codepoint);
            GlyphVector gv = this.actual.createGlyphVector(this.awtFrc, chars);
            Rectangle2D bounds = gv.getVisualBounds();

            int bw = Math.max(1, (int) Math.ceil(bounds.getWidth()));
            int bh = Math.max(1, (int) Math.ceil(bounds.getHeight()));
            int bearingX = (int) Math.floor(bounds.getX());
            int bearingY = (int) Math.floor(bounds.getY());

            BufferedImage glyphImage = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg = glyphImage.createGraphics();

            try {
                gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                gg.setFont(this.actual);
                gg.setColor(java.awt.Color.WHITE);
                gg.drawString(new String(chars), -bearingX, -bearingY);
            } finally {
                gg.dispose();
            }

            return new MinecraftGlyph(codepoint, PixelBuffer.wrap(glyphImage), advanceWidth, bearingX, bearingY);
        }

        /**
         * Resolves an {@code .otf} file using a 3-tier strategy and returns the loaded AWT font
         * alongside its filesystem location. Called exclusively from the enum constructor, so the
         * JVM's per-class {@code <clinit>} monitor (JLS 12.4.2) guarantees mutual exclusion across
         * enum values - no additional locks are required.
         *
         * <ol>
         *   <li><b>Classpath</b>: when {@code /fonts/<fileName>} resolves via the classloader, the
         *       bytes are copied into the filesystem cache so {@link #getPath} always points to a
         *       real file, then the font loads from that file.</li>
         *   <li><b>Filesystem cache</b>: check
         *       {@code DEFAULT_CACHE_ROOT/fonts/DEFAULT_VERSION/<fileName>} and load it directly.</li>
         *   <li><b>Auto-bootstrap</b>: invoke {@link ToolingFonts#generate} to produce the file, then
         *       retry tier 2.</li>
         * </ol>
         *
         * <p>On final miss, throws {@link IllegalStateException} naming every path that was tried.
         */
        private static @NotNull Resolved resolveFont(@NotNull String fileName) {
            String classpathPath = "fonts/" + fileName;
            Path cachedPath = defaultCacheRoot().resolve("fonts").resolve(DEFAULT_VERSION).resolve(fileName);

            // Tier 1: classpath. Materialize to filesystem cache so getPath() is always valid.
            try (InputStream cpStream = Vanilla.class.getClassLoader().getResourceAsStream(classpathPath)) {
                if (cpStream != null) {
                    Files.createDirectories(cachedPath.getParent());
                    Files.copy(cpStream, cachedPath, StandardCopyOption.REPLACE_EXISTING);
                    return new Resolved(createFontFromPath(cachedPath), cachedPath);
                }
            } catch (IOException ex) {
                // Classpath present but I/O failed; fall through to filesystem/bootstrap rather than fail-fast.
            }

            // Tier 2: filesystem cache.
            if (Files.isRegularFile(cachedPath))
                return new Resolved(createFontFromPath(cachedPath), cachedPath);

            // Tier 3: auto-bootstrap via ToolingFonts.
            try {
                ToolingFonts.generate(DEFAULT_VERSION, defaultCacheRoot());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath), ex);
            } catch (IOException ex) {
                throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath), ex);
            }

            if (Files.isRegularFile(cachedPath))
                return new Resolved(createFontFromPath(cachedPath), cachedPath);

            throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath));
        }

        /**
         * Reads an {@code .otf} file from disk, registers it with AWT, and derives to the native load size.
         */
        private static @NotNull Font createFontFromPath(@NotNull Path otfPath) {
            try (InputStream in = Files.newInputStream(otfPath)) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(FONT_POINT_SIZE);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                return font;
            } catch (IOException | FontFormatException ex) {
                throw new IllegalStateException("Unable to load font from file '" + otfPath + "'", ex);
            }
        }

        private static @NotNull String bootstrapFailureMessage(@NotNull String fileName, @NotNull String classpathPath, @NotNull Path cachedPath) {
            return String.format(
                "Unable to load font '%s' after all fallbacks.%n" +
                    "  Tier 1 (classpath): /%s%n" +
                    "  Tier 2 (filesystem cache): %s%n" +
                    "  Tier 3 (auto-bootstrap via ToolingFonts.generate): did not produce the expected file%n" +
                    "Fix: run `./gradlew :minecraft-text:fonts` to pre-warm the cache, " +
                    "or ensure `git` and Python 3.10+ are on PATH so auto-bootstrap can run.",
                fileName, classpathPath, cachedPath);
        }

        /**
         * Internal result of {@link #resolveFont}: the loaded AWT font plus the on-disk path of its {@code .otf}.
         */
        private record Resolved(@NotNull Font font, @NotNull Path path) {}

    }

    /**
     * A single pack colour font, keyed by {@link FontId}, that sits beside the fixed {@link Vanilla}
     * enum as the second {@link MinecraftFont} implementation.
     * <p>
     * It binds the three things a colour font needs at draw time: a {@code sbix} strike store over
     * its {@code .ttf} (a {@link SbixReader} plus a decode-once {@code (gid, ppem) -> PixelBuffer}
     * map), the parsed {@link ColorGlyphSidecar} view (advances, origins, strike selection), and a
     * vanilla {@link MinecraftFont} mono fallback for any codepoint the pack does not define. Every
     * codepoint resolves through {@link #glyph(int)} into a single {@link MinecraftGlyph}, so
     * downstream layout ({@link #layout(String)}), measurement ({@link #metrics()}), and painting
     * share one advance source and one glyph surface with the vanilla path.
     * <p>
     * The strike store is shared per FILE, not per font id (see {@link SharedStrikes}): with the
     * single merged per-pack colour font every font id of a pack points at the same {@code .ttf}
     * bytes, so one {@link SbixReader} and one strike cache back all of them rather than one copy per
     * id. The per-codepoint {@link #glyphCache} stays private to each instance because its rows differ
     * per font id.
     * <p>
     * Strikes are cached as {@link PixelBuffer} rather than {@link BufferedImage}: each strike is
     * decoded once via {@link ImageIO#read}, wrapped into an ARGB {@code int[]}, and the whole
     * {@code BufferedImage}/{@code Raster}/{@code ColorModel}/{@code SampleModel} object graph is then
     * discarded, shedding its fixed per-strike overhead for the process lifetime of the font.
     */
    final class Color implements MinecraftFont {

        /**
         * Classpath / cache subdirectory the colour {@code .ttf} files and their per-pack sidecars
         * live under.
         */
        public static final @NotNull String RESOURCE_DIR = "colorfont";

        /**
         * Filename suffix shared by every per-pack sidecar. The generator writes one sidecar per
         * pack, named {@code Minecraft-<Namespace>.colour-glyphs.json} beside the pack's merged
         * {@code Minecraft-<Namespace>.ttf}; {@link #sidecarNameFor(FontId)} derives the full
         * basename from a font id's namespace.
         */
        private static final @NotNull String SIDECAR_SUFFIX = "colour-glyphs.json";

        /**
         * The render context colour-font glyph codes resolve under: an identity transform with
         * antialiasing and fractional metrics on. A colour font never rasterizes vector outlines (its
         * art is {@code sbix} strikes), so the context only needs to be stable and shared - the merged
         * {@code cmap} lookups it feeds are transform-independent.
         */
        private static final @NotNull FontRenderContext COLOR_FRC = new FontRenderContext(new AffineTransform(), true, true);

        private final @NotNull FontId fontId;
        private final @NotNull SharedStrikes strikes;
        private final @NotNull ColorGlyphSidecar sidecar;
        private final @NotNull MinecraftFont monoFallback;
        private final @NotNull MinecraftFontMetrics metrics;

        /**
         * Per-font {@code codepoint -> cmap gid} cache backing the {@link MinecraftGlyphVector}
         * glyph-code surface for this font's non-raster glyphs (mono fallback and space providers);
         * raster glyphs report their sidecar gid directly and never reach this cache.
         */
        private final @NotNull ConcurrentMap<Integer, Integer> gidCache;

        /**
         * Per-codepoint glyph cache. Uniform {@link #glyph(int)} surface with the vanilla atlas over a
         * lower {@code sbix} strike tier. Stays private per instance - unlike the {@link #strikes}
         * store, the resolved rows differ per font id.
         */
        private final @NotNull ConcurrentMap<Integer, MinecraftGlyph> glyphCache;

        /**
         * This font's glyphs grouped by advance, built on the first {@link #glyphsByWidth()} and
         * {@code null} until then.
         */
        private volatile GlyphsByWidth glyphsByWidth;

        private Color(
            @NotNull FontId fontId,
            @NotNull SharedStrikes strikes,
            @NotNull ColorGlyphSidecar sidecar,
            @NotNull MinecraftFont monoFallback
        ) {
            this.fontId = fontId;
            this.strikes = strikes;
            this.sidecar = sidecar;
            this.monoFallback = monoFallback;
            this.glyphCache = Concurrent.newMap();
            this.gidCache = Concurrent.newMap();

            MinecraftFontMetrics mono = monoFallback.metrics();
            this.metrics = new MinecraftFontMetrics(this, mono.getFont(), mono.getAscent(), mono.getDescent(), mono.getHeight());
        }

        /**
         * Builds a colour font from raw font bytes, the parsed sidecar, and a mono fallback. The
         * strike store is internal, so callers pass only the {@code .ttf} bytes; the
         * {@link SbixReader} and its strike cache are shared with any other font built from identical
         * bytes (see {@link SharedStrikes}).
         *
         * @param fontId the font id
         * @param ttf the raw colour {@code .ttf} bytes
         * @param sidecar the parsed sidecar (may span multiple font ids)
         * @param monoFallback the vanilla font used for codepoints the pack does not define
         * @return the colour font
         */
        public static @NotNull Color of(
            @NotNull FontId fontId,
            byte @NotNull [] ttf,
            @NotNull ColorGlyphSidecar sidecar,
            @NotNull MinecraftFont monoFallback
        ) {
            return new Color(fontId, SharedStrikes.forBytes(ttf), sidecar, monoFallback);
        }

        /**
         * Resolves a colour font for a font id from the classpath, then the user-home cache, using
         * {@link Vanilla#REGULAR} as the mono fallback.
         *
         * @param fontId the font id to load
         * @return the resolved colour font
         * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
         */
        public static @NotNull Color load(@NotNull FontId fontId) {
            return load(fontId, Vanilla.REGULAR);
        }

        /**
         * Resolves a colour font for a font id with an explicit mono fallback.
         *
         * @param fontId the font id to load
         * @param monoFallback the vanilla font used for undefined codepoints
         * @return the resolved colour font
         * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
         */
        public static @NotNull Color load(@NotNull FontId fontId, @NotNull MinecraftFont monoFallback) {
            ColorGlyphSidecar sidecar = loadSidecar(fontId);
            String file = sidecar.fileFor(fontId).orElseThrow(() -> new IllegalStateException(
                "The resolved colour sidecar does not list font id '" + fontId + "'."));
            return of(fontId, loadTtfBytes(file), sidecar, monoFallback);
        }

        /**
         * Derives the per-pack sidecar resource basename for a font id, mirroring the generator's
         * naming rule so the runtime looks for exactly what the generator wrote.
         * <p>
         * The generator emits one merged sidecar per pack named
         * {@code Minecraft-<Namespace>.colour-glyphs.json}, beside the pack's merged
         * {@code Minecraft-<Namespace>.ttf}, where {@code <Namespace>} is the font id's
         * {@link FontId#namespace() namespace} with its first character upper-cased and the rest left
         * untouched (e.g. namespace {@code hypixel} yields {@code Minecraft-Hypixel.colour-glyphs.json}).
         * The {@code .ttf} basename itself is not derived here - it continues to come from the
         * resolved sidecar's {@code file} field.
         *
         * @param fontId the font id whose pack sidecar is being resolved
         * @return the per-pack sidecar resource basename
         */
        static @NotNull String sidecarNameFor(@NotNull FontId fontId) {
            String namespace = fontId.namespace();
            String capitalized = namespace.isEmpty()
                ? namespace
                : Character.toUpperCase(namespace.charAt(0)) + namespace.substring(1);
            return "Minecraft-" + capitalized + "." + SIDECAR_SUFFIX;
        }

        /**
         * Loads the colour sidecar for a font id by its per-pack name
         * ({@link #sidecarNameFor(FontId)}). A miss fails loud naming the attempted resource
         * basename and both lookup tiers.
         */
        private static @NotNull ColorGlyphSidecar loadSidecar(@NotNull FontId fontId) {
            return resolveSidecar(fontId, Color::loadSidecarNamed);
        }

        /**
         * Resolves the colour sidecar through an injected {@code name -> parsed sidecar} loader.
         * Package-private so the derived name and the fail-loud message can be exercised without
         * touching the classpath or the on-disk cache.
         *
         * @param fontId the font id whose sidecar is being resolved
         * @param loader resolves a sidecar resource basename to a parsed sidecar, or empty when absent
         * @return the resolved sidecar
         * @throws IllegalStateException when the per-pack name does not resolve
         */
        static @NotNull ColorGlyphSidecar resolveSidecar(
            @NotNull FontId fontId,
            @NotNull Function<String, Optional<ColorGlyphSidecar>> loader
        ) {
            String name = sidecarNameFor(fontId);
            return loader.apply(name)
                .orElseThrow(() -> new IllegalStateException(sidecarMissMessage(name)));
        }

        /**
         * Loads a single sidecar resource basename from the classpath, then the user-home cache,
         * returning empty when neither tier holds it.
         */
        private static @NotNull Optional<ColorGlyphSidecar> loadSidecarNamed(@NotNull String name) {
            String classpath = RESOURCE_DIR + "/" + name;
            try (InputStream in = Color.class.getClassLoader().getResourceAsStream(classpath)) {
                if (in != null) {
                    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        return Optional.of(ColorGlyphSidecar.parse(reader));
                    }
                }
            } catch (IOException ex) {
                // Fall through to the cache tier.
            }

            Path cached = MinecraftFont.defaultCacheRoot().resolve(RESOURCE_DIR).resolve(name);
            if (Files.isRegularFile(cached)) {
                try (Reader reader = Files.newBufferedReader(cached, StandardCharsets.UTF_8)) {
                    return Optional.of(ColorGlyphSidecar.parse(reader));
                } catch (IOException ex) {
                    throw new UncheckedIOException("Unable to read colour sidecar '" + cached + "'", ex);
                }
            }

            return Optional.empty();
        }

        private static @NotNull String sidecarMissMessage(@NotNull String name) {
            Path cacheDir = MinecraftFont.defaultCacheRoot().resolve(RESOURCE_DIR);
            return "Unable to load colour sidecar '" + name + "'.\n"
                + "  Looked up on Tier 1 (classpath /" + RESOURCE_DIR + "/) "
                + "and Tier 2 (filesystem cache " + cacheDir + ").";
        }

        private static byte[] loadTtfBytes(@NotNull String file) {
            String classpath = RESOURCE_DIR + "/" + file;
            try (InputStream in = Color.class.getClassLoader().getResourceAsStream(classpath)) {
                if (in != null) return in.readAllBytes();
            } catch (IOException ex) {
                // Fall through to the cache tier.
            }

            Path cached = MinecraftFont.defaultCacheRoot().resolve(RESOURCE_DIR).resolve(file);
            if (Files.isRegularFile(cached)) {
                try {
                    return Files.readAllBytes(cached);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Unable to read colour font '" + cached + "'", ex);
                }
            }

            throw new IllegalStateException(
                "Unable to load colour font '" + file + "' after all fallbacks.\n"
                    + "  Tier 1 (classpath): /" + classpath + "\n"
                    + "  Tier 2 (filesystem cache): " + cached);
        }

        @Override
        public @NotNull MinecraftGlyph glyph(int codepoint) {
            return this.glyphCache.computeIfAbsent(codepoint, this::resolveGlyph);
        }

        private @NotNull MinecraftGlyph resolveGlyph(int codepoint) {
            Optional<GlyphRow> rowOptional = this.sidecar.lookup(this.fontId, codepoint);
            if (rowOptional.isEmpty()) return this.monoFallback.glyph(codepoint);   // MONO fallback

            GlyphRow row = rowOptional.get();
            int unitsPerEm = this.sidecar.unitsPerEm();
            float advance = rowAdvance(row);
            if (row.isSpace()) return MinecraftGlyph.space(codepoint, advance);

            int ppem = resolvePpem(row);
            int gid = row.gid();
            int originX = (int) Math.round(FontUnits.toOutputPixels(row.originX(), unitsPerEm));
            int originY = (int) Math.round(FontUnits.toOutputPixels(row.originY(), unitsPerEm));

            // A raster row whose strike fails to decode degrades to an advance-only sentinel: the pen
            // still moves, nothing is painted, and glyph(cp) never returns null.
            return strike(gid, ppem)
                .map(bitmap -> MinecraftGlyph.color(codepoint, bitmap, advance, originX, originY, gid, ppem))
                .orElseGet(() -> MinecraftGlyph.space(codepoint, advance));
        }

        /**
         * Returns a row's advance in output pixels - the one value both its glyph and the obfuscation
         * table read.
         */
        private float rowAdvance(@NotNull GlyphRow row) {
            return (float) FontUnits.toOutputPixels(row.advance(), this.sidecar.unitsPerEm());
        }

        private int resolvePpem(@NotNull GlyphRow row) {
            Integer declared = row.strikePpem();
            if (declared != null) return declared;
            int[] available = this.strikes.reader().strikePpems();
            return available.length > 0 ? available[0] : 0;
        }

        /**
         * Returns the decoded {@code sbix} strike for a glyph as a {@link PixelBuffer}, decoding at
         * most once per {@code (gid, ppem)} and caching the result on the shared per-file store. Empty
         * when the glyph is absent in that strike or its graphic type is not a PNG.
         *
         * @param gid the glyph id
         * @param ppem the strike ppem
         * @return the decoded strike, or empty
         */
        @NotNull Optional<PixelBuffer> strike(int gid, int ppem) {
            return this.strikes.strike(gid, ppem);
        }

        @Override
        public @NotNull MinecraftFontMetrics metrics() {
            return this.metrics;
        }

        @Override
        public @NotNull FontId fontId() {
            return this.fontId;
        }

        @Override
        public @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
            return this.monoFallback.monoOutline(codepoint, penX);
        }

        @Override
        public @NotNull Font awtFont() {
            return this.strikes.awtFont();
        }

        @Override
        public @NotNull FontRenderContext fontRenderContext() {
            return COLOR_FRC;
        }

        @Override
        public int glyphCode(int codepoint) {
            return this.gidCache.computeIfAbsent(codepoint, cp -> resolveGlyphCode(awtFont(), COLOR_FRC, cp));
        }

        /** {@inheritDoc} */
        @Override
        public int obfuscate(int codepoint, @NotNull RandomGenerator random) {
            return glyphsByWidth().obfuscate(codepoint, random);
        }

        /**
         * Returns the glyphs obfuscated text in this font picks from, building them on first use: the
         * fallback's members at the advances its own table files them under - {@link Vanilla#REGULAR}'s
         * for a bold or italic fallback - then every row of this font id at its own advance, a row
         * taking the place of the fallback's glyph for its codepoint as {@link #glyph(int)} does.
         *
         * @return the table
         */
        @NotNull GlyphsByWidth glyphsByWidth() {
            GlyphsByWidth table = this.glyphsByWidth;
            if (table == null) {
                synchronized (this) {
                    table = this.glyphsByWidth;
                    if (table == null) {
                        table = buildGlyphsByWidth();
                        this.glyphsByWidth = table;
                    }
                }
            }
            return table;
        }

        private @NotNull GlyphsByWidth buildGlyphsByWidth() {
            GlyphsByWidth fallback = switch (this.monoFallback) {
                case Vanilla vanilla -> vanilla.glyphsByWidth();
                case Color color -> color.glyphsByWidth();
                case Missing missing -> missing.glyphsByWidth();
            };
            IntStream rowCodepoints = this.sidecar.rows(this.fontId).stream().mapToInt(GlyphRow::codepoint);

            return GlyphsByWidth.of(
                IntStream.concat(fallback.members(), rowCodepoints).toArray(),
                codepoint -> this.sidecar.lookup(this.fontId, codepoint)
                    .map(this::rowAdvance)
                    .orElseGet(() -> fallback.widthOf(codepoint))
            );
        }

        /**
         * @return the underlying {@code sbix} reader (shared per file)
         */
        @NotNull SbixReader reader() {
            return this.strikes.reader();
        }

        /**
         * @return the shared per-file strike store backing this font
         */
        @NotNull SharedStrikes strikes() {
            return this.strikes;
        }

        /**
         * @return the parsed sidecar
         */
        public @NotNull ColorGlyphSidecar sidecar() {
            return this.sidecar;
        }

        /**
         * @return the vanilla mono fallback font
         */
        public @NotNull MinecraftFont monoFallback() {
            return this.monoFallback;
        }

        /**
         * The {@code sbix} strike store shared across every {@link Color} font whose {@code .ttf}
         * bytes are byte-for-byte identical - which, with the single merged per-pack colour font, is
         * every font id of a pack. It owns the one {@link SbixReader} (holding the single ~2.8MB copy
         * of the font bytes) and the decode-once {@code (gid << 16 | ppem) -> PixelBuffer} cache, so a
         * reference pack's ~191 font ids share one reader and one set of decoded strikes instead of
         * ~191 copies.
         * <p>
         * <strong>Identity key.</strong> Entries are keyed by a SHA-256 content hash of the
         * {@code .ttf} bytes rather than a file path. {@link Color#of} receives raw bytes with no path
         * in hand (only {@link Color#load} resolves a filename), so a content hash is the only
         * identity available to both entry points, and it correctly folds together identical bytes
         * whether they arrive from the classpath, the filesystem cache, or an in-memory caller.
         * <p>
         * <strong>Lifetime.</strong> Process-lifetime: {@link #BY_CONTENT} is never evicted. This
         * mirrors the font {@link Registry}, which likewise holds pack fonts for the process lifetime,
         * and it is safe without a reference count because content-hash keying makes reloading
         * identical bytes an idempotent cache hit and a pack's glyph/strike set is finite. The strike
         * cache itself is unbounded for the same reason: the decoded strike set per file is bounded by
         * the font's glyphs.
         */
        @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
        static final class SharedStrikes {

            private static final @NotNull ConcurrentMap<String, SharedStrikes> BY_CONTENT = Concurrent.newMap();

            private final @NotNull SbixReader reader;
            private final byte @NotNull [] fontBytes;
            private final @NotNull ConcurrentMap<Long, Optional<PixelBuffer>> strikeCache = Concurrent.newMap();
            private volatile Font awtFont;

            /**
             * Returns the shared store for the given font bytes, constructing (and parsing) a
             * {@link SbixReader} exactly once per distinct byte content and reusing it thereafter. The
             * bytes are retained (the same array the reader already holds - no copy) so the backing AWT
             * font can be built lazily from them.
             *
             * @param ttf the raw colour {@code .ttf} bytes
             * @return the shared store keyed by the bytes' content hash
             */
            static @NotNull SharedStrikes forBytes(byte @NotNull [] ttf) {
                return BY_CONTENT.computeIfAbsent(contentKey(ttf), ignored -> new SharedStrikes(new SbixReader(ttf), ttf));
            }

            @NotNull SbixReader reader() {
                return this.reader;
            }

            /**
             * Lazily builds - then shares across every font id backed by these same bytes - the AWT
             * {@link Font} the merged colour {@code .ttf} maps to. It answers the
             * {@link MinecraftGlyphVector} glyph-code and {@link MinecraftGlyphVector#getFont() font}
             * surface honestly: its {@code cmap} yields the real gids and it is the font the vector
             * reports. It is never rasterized (colour art lives in {@code sbix}, which AWT paints
             * blank), so it is deliberately not registered with the {@link GraphicsEnvironment}.
             *
             * @return the backing AWT font, created once per distinct byte content
             */
            @NotNull Font awtFont() {
                Font font = this.awtFont;
                if (font == null) {
                    synchronized (this) {
                        font = this.awtFont;
                        if (font == null) {
                            font = createAwtFont(this.fontBytes);
                            this.awtFont = font;
                        }
                    }
                }
                return font;
            }

            private static @NotNull Font createAwtFont(byte @NotNull [] ttf) {
                try (InputStream in = new ByteArrayInputStream(ttf)) {
                    return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(FONT_POINT_SIZE);
                } catch (IOException | FontFormatException ex) {
                    throw new IllegalStateException("Unable to create the backing AWT font from the colour '.ttf' bytes", ex);
                }
            }

            @NotNull Optional<PixelBuffer> strike(int gid, int ppem) {
                long key = ((long) gid << 16) | (ppem & 0xFFFFL);
                return this.strikeCache.computeIfAbsent(key, ignored -> decode(gid, ppem));
            }

            private @NotNull Optional<PixelBuffer> decode(int gid, int ppem) {
                byte[] png = this.reader.strikePng(gid, ppem);
                if (png == null) return Optional.empty();
                try (InputStream in = new ByteArrayInputStream(png)) {
                    BufferedImage image = ImageIO.read(in);
                    if (image == null) return Optional.empty();
                    // Wrap into an ARGB int[] and drop the BufferedImage graph; the PixelBuffer is all we keep.
                    return Optional.of(PixelBuffer.wrap(toArgb(image)));
                } catch (IOException ex) {
                    throw new UncheckedIOException("Unable to decode sbix strike gid=" + gid + " ppem=" + ppem, ex);
                }
            }

            private static @NotNull String contentKey(byte @NotNull [] ttf) {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(ttf);
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                    return hex.toString();
                } catch (NoSuchAlgorithmException ex) {
                    throw new IllegalStateException("SHA-256 is required to key the shared colour strike store", ex);
                }
            }

            private static @NotNull BufferedImage toArgb(@NotNull BufferedImage source) {
                if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
                BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(source, 0, 0, null);
                return argb;
            }

        }

    }

    /**
     * The client's missing font, {@code minecraft:missing} - the font a run is drawn in where the
     * library draws no font for the run's id, as the client draws its missing font set for an id it
     * has no font under. It draws the client's missing glyph for every codepoint, U+0020 included.
     * <p>
     * The missing glyph is a hollow box 5 mcPixels wide and 8 tall, lit on its four edges, with its
     * left at the pen and its top on the ascent line, 7 mcPixels above the baseline; it moves the pen 6
     * mcPixels. Its texels are opaque white, so it is laid in the run's colour as a {@link Vanilla}
     * glyph is, and its outline is the box it draws. Its line metrics are {@link Vanilla#REGULAR}'s, as
     * the client lays every font out on one line height. It has one face, so a bold or italic run in it
     * draws the plain box, where the client offsets a bold one by an mcPixel and slants an italic one.
     * <p>
     * The font supports no codepoint, so it offers obfuscated text no candidate: every codepoint comes
     * back as itself with no draw, as the client's empty candidate table answers its missing glyph
     * again. A {@link Color} font over it as its mono fallback picks among its own rows alone, and draws
     * a codepoint it holds no row for as the missing glyph.
     * <p>
     * The glyph is a bitmap no AWT font holds, so the font has no backing AWT font and
     * {@link #awtFont()} throws {@link UnsupportedOperationException}. Nothing in the library reads one:
     * {@link MinecraftGraphics} blits the glyph records, {@link MinecraftFontMetrics} sums their
     * advances, and a {@link MinecraftGlyphVector} answers its bounds and glyph metrics from them, so
     * only a caller asking a vector laid out in this font for its
     * {@link MinecraftGlyphVector#getFont() AWT font} reaches it. Every codepoint's glyph code is
     * {@code 0}, the {@code .notdef} id a font gives the glyph it draws for a codepoint it lacks, and
     * its render context is an identity transform with antialiasing and fractional metrics off.
     */
    enum Missing implements MinecraftFont {

        INSTANCE;

        /**
         * The missing glyph's width, in mcPixels.
         */
        private static final int BOX_WIDTH = 5;

        /**
         * The missing glyph's height, in mcPixels.
         */
        private static final int BOX_HEIGHT = 8;

        /**
         * How far the missing glyph's top sits above the baseline, in mcPixels.
         */
        private static final int BOX_ASCENT = 7;

        /**
         * How far the missing glyph moves the pen, in mcPixels - its width and one more.
         */
        private static final int ADVANCE = BOX_WIDTH + 1;

        /**
         * The colour of a lit texel, opaque white.
         */
        private static final int LIT = 0xFFFFFFFF;

        private final @NotNull FontId fontId;

        /**
         * The missing glyph's pixels, which every codepoint's glyph shares.
         */
        private final @NotNull PixelBuffer box;

        /**
         * The box's fillable outline about the pen and the baseline, in output pixels.
         */
        private final @NotNull Shape outline;

        private final @NotNull MinecraftFontMetrics metrics;

        private final @NotNull FontRenderContext frc;

        /**
         * The glyphs obfuscated text in this font picks from - none, since the font supports no
         * codepoint, with every codepoint laid out at the missing glyph's advance.
         */
        private final @NotNull GlyphsByWidth glyphsByWidth;

        private final @NotNull ConcurrentMap<Integer, MinecraftGlyph> glyphCache;

        Missing() {
            this.fontId = new FontId("minecraft", "missing");
            this.box = drawBox();
            this.outline = outlineBox();
            this.frc = new FontRenderContext(null, false, false);
            this.glyphsByWidth = GlyphsByWidth.of(new int[0], codepoint -> ADVANCE * MC_PIXEL_SCALE);
            this.glyphCache = Concurrent.newMap();

            MinecraftFontMetrics regular = Vanilla.REGULAR.metrics();
            this.metrics = new MinecraftFontMetrics(this, regular.getFont(), regular.getAscent(), regular.getDescent(), regular.getHeight());
        }

        /**
         * Draws the missing glyph at {@link MinecraftFont#MC_PIXEL_SCALE} output pixels a texel, lit
         * where the client's is: on the box's first and last column and its first and last row.
         */
        private static @NotNull PixelBuffer drawBox() {
            PixelBuffer box = PixelBuffer.create(BOX_WIDTH * MC_PIXEL_SCALE, BOX_HEIGHT * MC_PIXEL_SCALE);

            for (int y = 0; y < box.height(); y++) {
                for (int x = 0; x < box.width(); x++) {
                    int column = x / MC_PIXEL_SCALE;
                    int row = y / MC_PIXEL_SCALE;

                    if (column == 0 || column + 1 == BOX_WIDTH || row == 0 || row + 1 == BOX_HEIGHT)
                        box.setPixel(x, y, LIT);
                }
            }

            return box;
        }

        /**
         * Traces the box {@link #drawBox()} lights as a fillable outline about the pen and the baseline,
         * in output pixels: the whole box less the texels inside its edges.
         */
        private static @NotNull Shape outlineBox() {
            int texel = MC_PIXEL_SCALE;
            Area outline = new Area(new Rectangle(0, -BOX_ASCENT * texel, BOX_WIDTH * texel, BOX_HEIGHT * texel));
            outline.subtract(new Area(new Rectangle(texel, (1 - BOX_ASCENT) * texel, (BOX_WIDTH - 2) * texel, (BOX_HEIGHT - 2) * texel)));
            return outline;
        }

        @Override
        public @NotNull MinecraftGlyph glyph(int codepoint) {
            return this.glyphCache.computeIfAbsent(codepoint, cp -> new MinecraftGlyph(cp, this.box, ADVANCE * MC_PIXEL_SCALE, 0, -BOX_ASCENT * MC_PIXEL_SCALE));
        }

        @Override
        public @NotNull MinecraftFontMetrics metrics() {
            return this.metrics;
        }

        @Override
        public @NotNull FontId fontId() {
            return this.fontId;
        }

        @Override
        public @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
            return Optional.of(AffineTransform.getTranslateInstance(penX, 0).createTransformedShape(this.outline));
        }

        @Override
        public @NotNull Font awtFont() {
            throw new UnsupportedOperationException("The missing font draws a bitmap no AWT font holds, so it has no backing AWT font");
        }

        @Override
        public @NotNull FontRenderContext fontRenderContext() {
            return this.frc;
        }

        @Override
        public int glyphCode(int codepoint) {
            return 0;
        }

        /** {@inheritDoc} */
        @Override
        public int obfuscate(int codepoint, @NotNull RandomGenerator random) {
            return this.glyphsByWidth.obfuscate(codepoint, random);
        }

        @NotNull GlyphsByWidth glyphsByWidth() {
            return this.glyphsByWidth;
        }

    }

    /**
     * The style category a {@link Vanilla} font entry belongs to.
     */
    @Getter
    @EnumLookup
    @RequiredArgsConstructor
    enum Style {

        REGULAR(0),
        BOLD(1),
        ITALIC(2),
        BOLD_ITALIC(3),
        GALACTIC(4),
        ILLAGERALT(5);

        @KeyField
        private final int id;

        /**
         * Returns the {@link Style} whose {@link #getId() id} matches the given value, or
         * {@link #REGULAR} when no match exists.
         *
         * @param id the style id to look up
         * @return the matching style, or {@link #REGULAR} when none matches
         */
        public static @NotNull Style of(int id) {
            return findById(id).orElse(REGULAR);
        }

    }

    /**
     * Holder for the deferred user-home cache root. Enum-constant construction happens before static
     * fields declared after the constants are initialized (JLS 12.4.1), so deferring the computation
     * behind a holder keeps {@link #defaultCacheRoot()} usable from the {@link Vanilla} constructor.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    final class DefaultsHolder {

        static final @NotNull Path CACHE_ROOT = computeDefaultCacheRoot();

        private static @NotNull Path computeDefaultCacheRoot() {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String userHome = System.getProperty("user.home", ".");
            if (osName.contains("win")) {
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank())
                    return Path.of(localAppData, "minecraft-library");
                return Path.of(userHome, "AppData", "Local", "minecraft-library");
            }
            return Path.of(userHome, ".cache", "minecraft-library");
        }

    }

    /**
     * Backing store for the fonts registered by id - pack fonts, and any font registered in place of
     * a {@link Vanilla} one. The vanilla fonts are looked up on {@link Vanilla} itself. A holder
     * class, because an interface's own fields are public.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    final class Registry {

        static final @NotNull ConcurrentMap<FontId, MinecraftFont> MAP = Concurrent.newMap();

    }

}
