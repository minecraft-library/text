package lib.minecraft.text.font;

import dev.simplified.image.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MinecraftGlyphVector lays out and paints pack colour text")
class MinecraftGlyphVectorTest {

    /**
     * A tooltip's fill as the client holds it, premultiplied - the canvas a see-through colour is laid over.
     */
    private static final int FILL = 0xF00F000F;

    /**
     * The tooltip's fill as straight colour, which a canvas filled with it holds as {@link #FILL}: {@code 0x10} at
     * alpha {@code 0xF0} is {@code 16 * 240 / 255 = 15.06}, held as {@code 0x0F}.
     */
    private static final int FILL_STRAIGHT = 0xF0100010;

    private static String cp(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    /**
     * Lists every pixel of a buffer that differs from what it should hold: {@code inside} within the area and
     * {@code outside} everywhere else.
     *
     * @param buffer the buffer under test
     * @param area the pixels that should hold {@code inside}
     * @param inside the ARGB expected within the area
     * @param outside the ARGB expected everywhere else
     * @return each misplaced pixel as its position, its value and the value expected
     */
    private static List<String> misplaced(PixelBuffer buffer, Rectangle area, int inside, int outside) {
        List<String> wrong = new ArrayList<>();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++) {
                int expected = area.contains(x, y) ? inside : outside;
                if (buffer.getPixel(x, y) != expected)
                    wrong.add(String.format("(%d,%d) %08X not %08X", x, y, buffer.getPixel(x, y), expected));
            }
        return wrong;
    }

    /**
     * Counts each value a buffer holds, keyed by its hex ARGB so a failure reads as colours.
     *
     * @param buffer the buffer to count
     * @return the number of pixels holding each value, in key order
     */
    private static Map<String, Integer> histogram(PixelBuffer buffer) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                counts.merge(String.format("%08X", buffer.getPixel(x, y)), 1, Integer::sum);
        return counts;
    }

    /**
     * Asserts two buffers hold the same pixels everywhere and counts the painted ones.
     *
     * @param actual the buffer under test
     * @param expected the reference buffer
     * @return the number of pixels with a non-zero alpha
     */
    private static int paintedMatching(PixelBuffer actual, PixelBuffer expected) {
        int painted = 0;
        for (int y = 0; y < expected.height(); y++)
            for (int x = 0; x < expected.width(); x++) {
                assertThat("pixel (" + x + "," + y + ")", actual.getPixel(x, y), is(expected.getPixel(x, y)));
                if ((actual.getPixel(x, y) >>> 24) != 0) painted++;
            }
        return painted;
    }

    @Test
    @DisplayName("pen positions come from cumulative sidecar advances, not GlyphVector positions")
    void layoutPositionsFromSidecar() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        assertThat(vector.glyphCount(), is(3));
        assertThat(vector.positionedGlyph(0).penX(), is(0.0));
        assertThat(vector.positionedGlyph(1).penX(), is(16.0));   // one em = 16 output px
        assertThat(vector.positionedGlyph(2).penX(), is(32.0));
        assertThat(vector.advanceX(), is(48.0));
        assertThat(vector.positionedGlyph(0).kind(), is(MinecraftGlyphVector.Kind.RASTER));
    }

    @Test
    @DisplayName("measure equals draw: MinecraftFontMetrics.stringAdvanceX == MinecraftGlyphVector.advanceX")
    void measureEqualsDraw() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_DOWNSCALED);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.advanceX(), is(font.metrics().stringAdvanceX(text)));
        assertThat(vector.advanceX(), closeTo(25.5, 1e-9));   // 16 + 1.5 + 8
    }

    @Test
    @DisplayName("a raster glyph paints its native strike pixels, byte-for-byte")
    void rasterBlitPixelIdentical() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));

        PixelBuffer target = PixelBuffer.canvas(16, 16);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.RED);

        PixelBuffer source = font.strike(ColorFontFixtures.GID_FLAT, 8).orElseThrow();
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                assertThat("pixel (" + x + "," + y + ")", target.getPixel(x, y), is(source.getPixel(x, y)));
    }

    @Test
    @DisplayName("a non-zero sidecar origin offsets the raster blit on both axes independently")
    void rasterGlyphOriginOffsetsBlitPosition() {
        // The committed fixture uses origin [0,0] everywhere, so the sidecar-origin -> bearing ->
        // blit-offset flow is otherwise unexercised: an axis swap or a sign flip would pass every
        // other test. This pins it. origin [128, 192] font units = (2, 3) output px (128 units per
        // mcPixel, MC_PIXEL_SCALE = 2), and 2 != 3 so a swapped axis lands on a background pixel.
        ColorGlyphSidecar sidecar = ColorGlyphSidecar.parse(new StringReader("""
            {
              "schema_version": 2, "units_per_em": 1024, "graphic_type": "png ",
              "file": "SynthColour.ttf",
              "glyphs": [
                {"font_id": "synth:demo", "codepoint": 57345, "stored_codepoint": 983041, "gid": 2,
                 "advance": 1024, "origin": [128, 192], "strike_ppem": 8}
              ]
            }
            """));
        MinecraftFont.Color font = MinecraftFont.Color.of(ColorFontFixtures.DEMO,
            ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF), sidecar, MinecraftFont.Vanilla.REGULAR);

        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));
        MinecraftGlyph glyph = vector.positionedGlyph(0);
        assertThat(glyph.originX(), is(2));
        assertThat(glyph.originY(), is(3));

        PixelBuffer target = PixelBuffer.canvas(16, 16);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.WHITE);

        // The strike's opaque top-left pixel lands at (originX, originY).
        assertThat("origin offset applied", target.getPixel(2, 3), is(0xFFDC2828));
        assertThat("nothing at the un-offset origin", target.getPixel(0, 0), is(0));
        assertThat("a swapped axis would paint here", target.getPixel(3, 2), is(0));
    }

    @Test
    @DisplayName("a colour raster glyph is never tinted by the fill colour, its alpha included")
    void rasterGlyphNotTinted() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));

        // Each fill would tint a mono glyph green; the see-through two would also scale or discard its pixels.
        for (Color fill : List.of(Color.GREEN, new Color(0, 255, 0, 25), new Color(0, 255, 0, 0x80))) {
            PixelBuffer target = PixelBuffer.canvas(16, 16);
            target.fill(0);
            MinecraftGraphics graphics = new MinecraftGraphics(target);
            vector.paint(graphics, 0, 0, fill);

            String label = String.format("fill %08X", fill.getRGB());
            assertThat(label + ", the strike's red", target.getPixel(0, 0), is(0xFFDC2828));
            assertThat(label + ", the strike's blue", target.getPixel(4, 4), is(0xFF283CDC));
        }
    }

    @Test
    @DisplayName("a negative-advance space provider moves the pen backward and paints nothing")
    void spaceProviderNegativeAdvance() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_NEG_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.positionedGlyph(1).kind(), is(MinecraftGlyphVector.Kind.SPACE));
        assertThat(vector.positionedGlyph(1).signedAdvance(), is(-16.0f));
        assertThat(vector.positionedGlyph(2).penX(), is(0.0));   // 16 + (-16) back to origin
        assertThat(vector.positionedGlyph(1).bitmap().width(), is(1));   // SPACE sentinel bitmap, never null
        assertThat(vector.advanceX(), is(16.0));
    }

    @Test
    @DisplayName("a fractional pen rounds identically for measure and draw")
    void fractionalAdvanceRoundingConsistent() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.positionedGlyph(2).penX(), closeTo(17.5, 1e-9));
        assertThat(vector.advanceX(), closeTo(33.5, 1e-9));
        assertThat(Math.round((float) vector.advanceX()), is(Math.round((float) font.metrics().stringAdvanceX(text))));
    }

    @Test
    @DisplayName("a codepoint not in the sidecar falls back to the vanilla mono atlas")
    void monoFallbackForUnlistedCodepoint() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout("A");   // 0x41 is not a pack colour glyph

        MinecraftGlyph glyph = vector.positionedGlyph(0);
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.MONO));
        assertThat(glyph.color(), is(false));
        assertThat(glyph.signedAdvance(), is((float) MinecraftFont.Vanilla.REGULAR.glyph('A').advanceWidth()));
    }

    @Test
    @DisplayName("the same PUA codepoint resolves to different artwork under different font ids")
    void puaCollisionDisambiguatedByFontId() {
        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        MinecraftFont.Color alt = ColorFontFixtures.altFont();

        // the same original codepoint (E001) resolves to different merged gids per font id
        PixelBuffer demoStrike = demo.strike(ColorFontFixtures.GID_FLAT, 8).orElseThrow();
        PixelBuffer altStrike = alt.strike(ColorFontFixtures.GID_ALT_FLAT, 8).orElseThrow();

        assertThat(demoStrike.getPixel(0, 0), is(0xFFDC2828));   // demo E001 top-left red
        assertThat(altStrike.getPixel(0, 0), is(0xFF28C83C));    // alt E001 top-left green (40,200,60)
        assertFalse(demoStrike.getPixel(0, 0) == altStrike.getPixel(0, 0));
    }

    @Test
    @DisplayName("strike selection follows the sidecar strike_ppem (downscaled art uses a larger ppem)")
    void strikeSelectionFollowsPpem() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector flat = font.layout(cp(ColorFontFixtures.CP_FLAT));
        MinecraftGlyphVector downscaled = font.layout(cp(ColorFontFixtures.CP_DOWNSCALED));

        assertThat(flat.positionedGlyph(0).strikePpem(), is(8));
        assertThat(downscaled.positionedGlyph(0).strikePpem(), is(16));

        // The ppem-16 strike carries the downscaled art (top-left red 200,30,30).
        assertThat(font.strike(ColorFontFixtures.GID_DOWNSCALED, 16).orElseThrow().getPixel(0, 0), is(0xFFC81E1E));
    }

    @Test
    @DisplayName("knockout subtracts overlapping mono outlines and is empty for raster glyphs")
    void knockoutAreaOnMonoOutlines() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout("AB" + cp(ColorFontFixtures.CP_FLAT));

        assertTrue(vector.outline(0).isPresent());   // mono A
        assertTrue(vector.outline(1).isPresent());   // mono B
        assertFalse(vector.outline(2).isPresent());  // raster glyph has no outline

        assertFalse(vector.knockout(0, 1).isEmpty());   // mono over mono -> real area
        assertTrue(vector.knockout(2, 1).isEmpty());     // raster over mono -> empty
        assertTrue(vector.knockout(0, 2).isEmpty());     // mono over raster -> empty
    }

    @Test
    @DisplayName("regression: Java2D's GlyphVector advance for an sbix glyph is a fraction of a pixel - our layout must not trust it")
    void glyphVectorAdvanceIsNotTheSidecarWidth() throws Exception {
        Font awtFont = Font.createFont(Font.TRUETYPE_FONT,
            new java.io.ByteArrayInputStream(ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF))).deriveFont(16.0f);
        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        int stored = 0xF0001;   // demo E001's stored codepoint in the merged font; E001 itself maps to .notdef there
        GlyphVector awt = awtFont.createGlyphVector(frc, cp(stored).repeat(3));
        assertThat("Java2D resolves the stored codepoint to demo E001's glyph", awt.getGlyphCode(0), is(ColorFontFixtures.GID_FLAT));
        double awtAdvance = awt.getGlyphPosition(awt.getNumGlyphs()).getX();

        MinecraftGlyphVector ours = ColorFontFixtures.demoFont().layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        // Java2D answers a fraction of a pixel for three sbix glyphs; the sidecar's advance is three 16 px ems.
        assertThat(awtAdvance, lessThan(1.0));
        assertThat(ours.advanceX(), is(48.0));
    }

    @Test
    @DisplayName("backcompat: drawString still renders vanilla text into the buffer")
    void drawStringStillRenders() {
        PixelBuffer target = PixelBuffer.canvas(64, 32);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        graphics.setColor(Color.WHITE);
        graphics.drawString("Hi", 1, 10);

        int painted = 0;
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++)
                if ((target.getPixel(x, y) >>> 24) != 0) painted++;
        assertThat(painted, greaterThan(0));
    }

    @Test
    @DisplayName("drawString renders byte-identically to an explicit layout + paint for vanilla text")
    void drawStringEqualsLayoutPaint() {
        String text = "Hello, World!";

        PixelBuffer viaDrawString = PixelBuffer.canvas(160, 32);
        viaDrawString.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(viaDrawString);
        g1.setColor(Color.WHITE);
        g1.drawString(text, 2, 20);

        PixelBuffer viaLayoutPaint = PixelBuffer.canvas(160, 32);
        viaLayoutPaint.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(viaLayoutPaint);
        MinecraftFont.Vanilla.REGULAR.layout(text).paint(g2, 2, 20, Color.WHITE);

        int painted = 0;
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 160; x++) {
                assertThat("pixel (" + x + "," + y + ")", viaDrawString.getPixel(x, y), is(viaLayoutPaint.getPixel(x, y)));
                if ((viaDrawString.getPixel(x, y) >>> 24) != 0) painted++;
            }
        assertThat("the run actually painted glyphs", painted, greaterThan(0));
    }

    @Test
    @DisplayName("drawString at a half-mcPixel origin lands one output pixel from the whole-mcPixel origin")
    void drawStringAtHalfMcPixel() {
        PixelBuffer half = PixelBuffer.canvas(64, 32);
        half.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(half);
        g1.setColor(Color.WHITE);
        g1.drawString("Hi", 1.5f, 10.5f);

        PixelBuffer shifted = PixelBuffer.canvas(64, 32);
        shifted.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(shifted);
        g2.setColor(Color.WHITE);
        g2.translate(1, 1);   // one output pixel each way
        g2.drawString("Hi", 1, 10);

        assertThat("the run actually painted glyphs", paintedMatching(half, shifted), greaterThan(0));
    }

    @Test
    @DisplayName("drawGlyphVector at a half-mcPixel origin lands one output pixel from the whole-mcPixel origin")
    void drawGlyphVectorAtHalfMcPixel() {
        MinecraftGlyphVector vector = MinecraftFont.Vanilla.REGULAR.layout("Hi");

        PixelBuffer half = PixelBuffer.canvas(64, 32);
        half.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(half);
        g1.setColor(Color.WHITE);
        g1.drawGlyphVector((GlyphVector) vector, 1.5f, 10.5f);

        PixelBuffer shifted = PixelBuffer.canvas(64, 32);
        shifted.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(shifted);
        g2.translate(1, 1);   // one output pixel each way
        vector.paint(g2, 1, 10, Color.WHITE);

        assertThat("the run actually painted glyphs", paintedMatching(half, shifted), greaterThan(0));
    }

    @Test
    @DisplayName("a mono glyph pixel takes its colour's alpha and is not laid at 25 or less, as the client's text shader discards below 0.1")
    void monoGlyphPixelTakesItsColoursAlpha() {
        PixelBuffer bitmap = PixelBuffer.straight(2, 1);
        bitmap.fill(0xFFFFFFFF);
        MinecraftGlyph glyph = new MinecraftGlyph('x', bitmap, 4, 0, 0);
        Rectangle laid = new Rectangle(1, 1, 2, 1);

        // The client's bytes: the texel times the colour's alpha, blended translucent over the fill.
        assertAll("a 2x1 opaque white glyph laid over " + String.format("%08X", FILL),
            () -> assertThat("in 0x80FF0000", misplaced(blitOver(glyph, 0x80FF0000), laid, 0xF8870007, FILL), is(empty())),
            () -> assertThat("in 0x1AFF0000", misplaced(blitOver(glyph, 0x1AFF0000), laid, 0xF227000D, FILL), is(empty())),
            () -> assertThat("in 0x19FF0000", misplaced(blitOver(glyph, 0x19FF0000), laid, FILL, FILL), is(empty())),
            () -> assertThat("in 0x00000000", misplaced(blitOver(glyph, 0x00000000), laid, FILL, FILL), is(empty())),
            () -> assertThat("in 0xFFFF0000", misplaced(blitOver(glyph, 0xFFFF0000), laid, 0xFFFF0000, FILL), is(empty()))
        );
    }

    @Test
    @DisplayName("a mono glyph pixel is laid in the convention its buffer holds: onto straight colour it stays straight colour")
    void monoGlyphPixelLaysInItsBuffersConvention() {
        PixelBuffer bitmap = PixelBuffer.straight(2, 1);
        bitmap.fill(0xFFFFFFFF);
        MinecraftGlyph glyph = new MinecraftGlyph('x', bitmap, 4, 0, 0);
        Rectangle laid = new Rectangle(1, 1, 2, 1);

        // Over transparency the straight formula lays the colour as given. Over 0xF0100010 the colour weighs
        // 128 * 255 = 32640 and the pixel 240 * 127 = 30480 of 63120: red (255 * 32640 + 16 * 30480) / 63120 = 140,
        // blue 16 * 30480 / 63120 = 8, alpha 63120 / 255 = 248.
        assertAll("a 2x1 opaque white glyph laid in 0x80FF0000 on a straight buffer",
            () -> assertThat("over transparency", misplaced(blitOnto(PixelBuffer.straight(4, 3), 0, glyph, 0x80FF0000), laid, 0x80FF0000, 0), is(empty())),
            () -> assertThat("over 0xF0100010", misplaced(blitOnto(PixelBuffer.straight(4, 3), FILL_STRAIGHT, glyph, 0x80FF0000), laid, 0xF88C0008, FILL_STRAIGHT), is(empty()))
        );
    }

    @Test
    @DisplayName("a colour glyph's see-through pixel is laid in the convention its buffer holds, as a mono glyph's is")
    void colourGlyphPixelLaysInItsBuffersConvention() {
        PixelBuffer bitmap = PixelBuffer.straight(2, 1);
        bitmap.fill(0x80FF0000);
        MinecraftGlyph glyph = MinecraftGlyph.color(0xE001, bitmap, 4f, 0, 0);
        Rectangle laid = new Rectangle(1, 1, 2, 1);

        // The tint reaches no pixel of a colour glyph, so each is laid in white and lays the strike's 0x80FF0000.
        assertAll("a 2x1 colour glyph of 0x80FF0000",
            () -> assertThat("on a canvas over " + String.format("%08X", FILL), misplaced(blitOver(glyph, 0xFFFFFFFF), laid, 0xF8870007, FILL), is(empty())),
            () -> assertThat("on a straight buffer over transparency", misplaced(blitOnto(PixelBuffer.straight(4, 3), 0, glyph, 0xFFFFFFFF), laid, 0x80FF0000, 0), is(empty())),
            () -> assertThat("on a straight buffer over 0xF0100010", misplaced(blitOnto(PixelBuffer.straight(4, 3), FILL_STRAIGHT, glyph, 0xFFFFFFFF), laid, 0xF88C0008, FILL_STRAIGHT), is(empty()))
        );
    }

    /**
     * Blits a glyph at {@code (1, 1)} onto a 4x3 canvas holding {@link #FILL}.
     *
     * @param glyph the glyph to blit
     * @param argb the colour to blit it in
     * @return the canvas the glyph was blitted onto
     */
    private static PixelBuffer blitOver(MinecraftGlyph glyph, int argb) {
        return blitOnto(PixelBuffer.canvas(4, 3), FILL_STRAIGHT, glyph, argb);
    }

    /**
     * Fills a buffer with a straight colour, laid in the convention the buffer holds, and blits a glyph onto it at
     * {@code (1, 1)}.
     *
     * @param target the buffer to blit onto
     * @param fill the straight colour to fill it with first
     * @param glyph the glyph to blit
     * @param argb the colour to blit it in
     * @return the buffer the glyph was blitted onto
     */
    private static PixelBuffer blitOnto(PixelBuffer target, int fill, MinecraftGlyph glyph, int argb) {
        target.fill(fill);
        new MinecraftGraphics(target).blitGlyph(glyph, 1, 1, argb);
        return target;
    }

    @Test
    @DisplayName("drawString lays its colour's alpha: a colour the text shader discards draws nothing, a see-through one blends")
    void drawStringLaysItsColoursAlpha() {
        PixelBuffer discarded = drawnOver(new Color(0x19FFFFFF, true));
        PixelBuffer halfRed = drawnOver(new Color(0x80FF0000, true));

        assertAll("'Hi' drawn over " + String.format("%08X", FILL),
            () -> assertThat("in 0x19FFFFFF", histogram(discarded), is(Map.of("F00F000F", 64 * 32))),
            () -> assertThat("in 0x80FF0000", histogram(halfRed).keySet(), is(Set.of("F00F000F", "F8870007")))
        );
    }

    /**
     * Draws {@code "Hi"} through {@link MinecraftGraphics#drawString(String, float, float)} onto a 64x32 canvas
     * holding {@link #FILL}.
     *
     * @param colour the colour to draw in
     * @return the canvas the run was drawn onto
     */
    private static PixelBuffer drawnOver(Color colour) {
        PixelBuffer target = PixelBuffer.canvas(64, 32);
        target.fill(FILL_STRAIGHT);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        graphics.setColor(colour);
        graphics.drawString("Hi", 1f, 10f);
        return target;
    }

    @Test
    @DisplayName("a glyph laid on a transparent scratch canvas and scaled onto a canvas is scaled by its alpha once")
    void aScratchCanvasScalesItsGlyphByAlphaOnce() {
        PixelBuffer bitmap = PixelBuffer.straight(1, 1);
        bitmap.fill(0xFFFFFFFF);
        MinecraftGlyph glyph = new MinecraftGlyph('x', bitmap, 2, 0, 0);

        PixelBuffer scratch = PixelBuffer.canvas(1, 1);
        new MinecraftGraphics(scratch).blitGlyph(glyph, 0, 0, 0x80FF0000);
        PixelBuffer target = PixelBuffer.canvas(4, 3);
        target.fill(FILL_STRAIGHT);
        target.blitScaled(scratch, 1, 1, 2, 2);

        // 0x80FF0000 over transparency is 0xFF * 128 / 255 = 128 red at alpha 128, premultiplied; laid premultiplied
        // over FILL it is 128 + round(15 * 127 / 255) = 135 red, round(15 * 127 / 255) = 7 blue and
        // round(128 + 240 * 127 / 255) = 248 alpha, the 0xF8870007 laying the glyph onto FILL directly lays. The two
        // agree exactly here because channels of 0xFF and 0 premultiply without rounding.
        assertAll("0x80FF0000 laid on a 1x1 scratch canvas and scaled to 2x2 at (1, 1) over " + String.format("%08X", FILL),
            () -> assertThat("the scratch", scratch.getPixel(0, 0), is(0x80800000)),
            () -> assertThat("the canvas it is scaled onto", misplaced(target, new Rectangle(1, 1, 2, 2), 0xF8870007, FILL), is(empty()))
        );
    }

    @Test
    @DisplayName("MinecraftGlyphVector#paint in a colour of alpha 25 paints nothing - one lay-down behind every entry point")
    void paintInADiscardedColourPaintsNothing() {
        PixelBuffer target = PixelBuffer.canvas(64, 32);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        MinecraftFont.Vanilla.REGULAR.layout("Hi").paint(graphics, 1, 10, new Color(255, 255, 255, 25));

        assertThat(histogram(target), is(Map.of("00000000", 64 * 32)));
    }

    @Test
    @DisplayName("a run drawn at another's mcPixel width continues its pen, half pixel included")
    void nextRunContinuesThePen() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String head = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE).repeat(2);   // 16 + 1.5 + 1.5 = 19 output px
        String tail = cp(ColorFontFixtures.CP_FLAT);
        float headMcPx = font.metrics().stringAdvanceMcPixels(head);
        assertThat(headMcPx, is(9.5f));

        PixelBuffer joined = PixelBuffer.canvas(48, 16);
        joined.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(joined);
        g1.setFont(font);
        g1.setColor(Color.WHITE);
        g1.drawString(head + tail, 0f, 0f);

        PixelBuffer split = PixelBuffer.canvas(48, 16);
        split.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(split);
        g2.setFont(font);
        g2.setColor(Color.WHITE);
        g2.drawString(head, 0f, 0f);
        g2.drawString(tail, headMcPx, 0f);

        assertThat("the runs actually painted glyphs", paintedMatching(split, joined), greaterThan(0));
        assertThat("the tail's strike top-left sits at the head's 19 output px", split.getPixel(19, 0), is(0xFFDC2828));
    }

    @Test
    @DisplayName("a run split at a quarter mcPixel lands where the joined run does - origin and pen round once, together")
    void splitAtQuarterMcPixelRoundsOnce() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String head = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE);   // 16 + 1.5 = 17.5 output px
        String tail = cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);   // the strike's pen is 1.5
        float headMcPx = font.metrics().stringAdvanceMcPixels(head);
        assertThat(headMcPx, is(8.75f));

        PixelBuffer joined = PixelBuffer.canvas(48, 16);
        joined.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(joined);
        g1.setFont(font);
        g1.setColor(Color.WHITE);
        g1.drawString(head + tail, 0f, 0f);

        PixelBuffer viaDraw = PixelBuffer.canvas(48, 16);
        viaDraw.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(viaDraw);
        g2.setFont(font);
        g2.setColor(Color.WHITE);
        g2.drawString(head, 0f, 0f);
        g2.drawString(tail, headMcPx, 0f);

        PixelBuffer viaVector = PixelBuffer.canvas(48, 16);
        viaVector.fill(0);
        MinecraftGraphics g3 = new MinecraftGraphics(viaVector);
        g3.drawGlyphVector(font.layout(head), 0f, 0f, Color.WHITE);
        g3.drawGlyphVector(font.layout(tail), headMcPx, 0f, Color.WHITE);

        // One rounding puts the tail's strike at round(17.5 + 1.5) = 19; rounding origin and pen apart
        // puts it at round(17.5) + round(1.5) = 20.
        assertThat("drawString: the runs actually painted glyphs", paintedMatching(viaDraw, joined), greaterThan(0));
        assertThat("drawGlyphVector: the runs actually painted glyphs", paintedMatching(viaVector, joined), greaterThan(0));
        assertThat("the tail's strike top-left sits at 19 output px", viaDraw.getPixel(19, 0), is(0xFFDC2828));
        assertThat("nothing left of the tail's strike", viaDraw.getPixel(18, 0), is(0));
    }

    @Test
    @DisplayName("stringAdvanceMcPixels walks codepoints, so a surrogate pair is one glyph")
    void stringAdvanceMcPixelsWalksCodepoints() {
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        int astral = 0x1D400;   // MATHEMATICAL BOLD CAPITAL A, a surrogate pair in UTF-16
        String pair = new String(Character.toChars(astral));
        assertThat(pair.length(), is(2));
        assertThat(font.metrics().stringAdvanceMcPixels(pair), is(font.glyph(astral).advanceWidth() / 2f));
    }

    @Test
    @DisplayName("stringWidthMcPixels rounds a run ending on half an mcPixel up, as the client's Font#width does")
    void stringWidthMcPixelsRoundsUp() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String half = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE).repeat(2);   // 9.5 mcPx
        String whole = cp(ColorFontFixtures.CP_FLAT);                                                  // 8 mcPx
        assertThat(font.metrics().stringAdvanceMcPixels(half), is(9.5f));
        String quarter = cp(ColorFontFixtures.CP_FRAC_SPACE).repeat(3);                               // 2.25 mcPx
        assertThat(font.metrics().stringWidthMcPixels(half), is(10));
        assertThat(font.metrics().stringWidthMcPixels(whole), is(8));
        assertThat(font.metrics().stringAdvanceMcPixels(quarter), is(2.25f));
        assertThat(font.metrics().stringWidthMcPixels(quarter), is(3));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels keeps a codepoint ending exactly on the width, as the client's Font#plainSubstrByWidth does")
    void stringHeadByWidthMcPixelsKeepsAnExactFit() {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();   // 'A' is 6 mcPx
        assertThat(metrics.stringHeadByWidthMcPixels("AAA", 12), is("AA"));
        assertThat(metrics.stringHeadByWidthMcPixels("AAA", 11), is("A"));
        assertThat(metrics.stringHeadByWidthMcPixels("AAA", 18), is("AAA"));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels drops a codepoint ending half an mcPixel past the width, and sums half advances exactly")
    void stringHeadByWidthMcPixelsDropsAHalfPixelOverrun() {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();
        String star = cp(0x2726);   // BLACK FOUR POINTED STAR, 7.5 mcPx
        assertThat(metrics.stringAdvanceMcPixels("A" + star), is(13.5f));
        assertThat(metrics.stringHeadByWidthMcPixels("A" + star, 13), is("A"));
        assertThat(metrics.stringHeadByWidthMcPixels("A" + star, 14), is("A" + star));
        assertThat(metrics.stringHeadByWidthMcPixels(star + star, 15), is(star + star));
        assertThat(metrics.stringHeadByWidthMcPixels(star + star, 14), is(star));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels keeps or drops a surrogate pair whole")
    void stringHeadByWidthMcPixelsKeepsASurrogatePairWhole() {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();
        String astral = cp(0x1D400);   // MATHEMATICAL BOLD CAPITAL A, 9 mcPx, two chars
        String text = "A" + astral + "A";   // 6 + 9 + 6 = 21 mcPx
        assertThat(metrics.stringAdvanceMcPixels(astral), is(9f));
        assertThat(metrics.stringHeadByWidthMcPixels(text, 15), is("A" + astral));
        assertThat(metrics.stringHeadByWidthMcPixels(text, 14), is("A"));
        assertThat(metrics.stringHeadByWidthMcPixels(text, 21), is(text));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels answers nothing for empty text or a width its first codepoint overruns")
    void stringHeadByWidthMcPixelsOfNothing() {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();
        assertThat(metrics.stringHeadByWidthMcPixels("", 10), is(""));
        assertThat(metrics.stringHeadByWidthMcPixels("", 0), is(""));
        assertThat(metrics.stringHeadByWidthMcPixels("A", 0), is(""));
        assertThat(metrics.stringHeadByWidthMcPixels("A", 5), is(""));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels stops at the first codepoint that overruns, though a negative advance after it would bring the run back")
    void stringHeadByWidthMcPixelsStopsAtTheFirstOverrun() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String flat = cp(ColorFontFixtures.CP_FLAT);        // 8 mcPx
        String back = cp(ColorFontFixtures.CP_NEG_SPACE);   // -8 mcPx
        assertThat(font.metrics().stringAdvanceMcPixels(flat + flat + back), is(8f));
        assertThat(font.metrics().stringHeadByWidthMcPixels(flat + flat + back, 8), is(flat));
    }

    @Test
    @DisplayName("stringHeadByWidthMcPixels cuts 'Enchanted Golden Apple' at 103 mcPx after its 'A', the 'p' reaching 105")
    void stringHeadByWidthMcPixelsCutsAnItemName() {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();
        assertThat(metrics.stringAdvanceMcPixels("Enchanted Golden A"), is(99f));
        assertThat(metrics.stringHeadByWidthMcPixels("Enchanted Golden Apple", 103), is("Enchanted Golden A"));
    }

    @Test
    @DisplayName("a mixed mono + colour + space run paints through one layout call with zero caller branching")
    void mixedRunPaintsThroughOneCall() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = "A" + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftGlyphVector vector = font.layout(text);

        // Every kind arrives through the one glyph() surface - the caller never branches on font kind.
        assertThat(vector.positionedGlyph(0).kind(), is(MinecraftGlyphVector.Kind.MONO));    // 'A'
        assertThat(vector.positionedGlyph(1).kind(), is(MinecraftGlyphVector.Kind.SPACE));   // fractional-advance space
        assertThat(vector.positionedGlyph(2).kind(), is(MinecraftGlyphVector.Kind.RASTER));  // flat colour glyph

        PixelBuffer target = PixelBuffer.canvas(96, 48);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 16, Color.WHITE);   // one call renders mono + colour + space together

        boolean whiteMonoPixel = false;
        boolean nativeRedRasterPixel = false;
        for (int y = 0; y < 48 && !(whiteMonoPixel && nativeRedRasterPixel); y++)
            for (int x = 0; x < 96; x++) {
                int pixel = target.getPixel(x, y);
                if (pixel == 0xFFFFFFFF) whiteMonoPixel = true;        // the mono 'A' tinted white
                if (pixel == 0xFFDC2828) nativeRedRasterPixel = true;  // the colour glyph's untinted red
            }
        assertTrue(whiteMonoPixel, "expected the tinted mono glyph in the buffer");
        assertTrue(nativeRedRasterPixel, "expected the untinted colour glyph in the buffer");
    }

    @Test
    @DisplayName("fillEffect lays a bar given in mcPixels as a glyph pixel is laid: its colour's alpha, the text shader's cut, the translucent blend")
    void fillEffectLaysABarAsAGlyphPixel() {
        PixelBuffer halfRed = effectCanvas();
        MinecraftGraphics g1 = new MinecraftGraphics(halfRed);
        g1.setColor(new Color(0x80FF0000, true));
        g1.fillEffect(1f, 1f, 3f, 1f);

        PixelBuffer discarded = effectCanvas();
        MinecraftGraphics g2 = new MinecraftGraphics(discarded);
        g2.setColor(new Color(0x19FF0000, true));
        g2.fillEffect(1f, 1f, 3f, 1f);

        assertAll("a 3x1 mcPixel bar at (1, 1) over " + String.format("%08X", FILL),
            () -> assertThat("in 0x80FF0000, the 6x2 output pixels from (2, 2)", misplaced(halfRed, new Rectangle(2, 2, 6, 2), 0xF8870007, FILL), is(empty())),
            () -> assertThat("in 0x19FF0000, nothing", misplaced(discarded, new Rectangle(), FILL, FILL), is(empty()))
        );
    }

    @Test
    @DisplayName("fillEffect converts each edge under the translation and rounds it half up once, so a width is the difference of two rounded edges")
    void fillEffectRoundsEachEdgeOnce() {
        PixelBuffer wholeWidth = effectCanvas();
        MinecraftGraphics g1 = new MinecraftGraphics(wholeWidth);
        g1.setColor(new Color(0x80FF0000, true));
        g1.translate(1, 0);
        g1.fillEffect(0.25f, 0.5f, 1.5f, 1f);

        PixelBuffer halfWidth = effectCanvas();
        MinecraftGraphics g2 = new MinecraftGraphics(halfWidth);
        g2.setColor(new Color(0x80FF0000, true));
        g2.translate(1, 0);
        g2.fillEffect(0.25f, 0.5f, 1.25f, 1f);

        // Left 1 + 0.5 = 1.5 -> 2 and top 1 -> 1, bottom 1 + 2 = 3 -> 3, for both. Right 1.5 + 3 = 4.5 -> 5 for the
        // first; 1.5 + 2.5 = 4 -> 4 for the second, where rounding the 2.5 output-pixel width alone would reach 5.
        assertAll("a bar at (0.25, 0.5) mcPx under translate(1, 0)",
            () -> assertThat("1.5 mcPx wide, columns 2-4 and rows 1-2", misplaced(wholeWidth, new Rectangle(2, 1, 3, 2), 0xF8870007, FILL), is(empty())),
            () -> assertThat("1.25 mcPx wide, columns 2-3 and rows 1-2", misplaced(halfWidth, new Rectangle(2, 1, 2, 2), 0xF8870007, FILL), is(empty()))
        );
    }

    @Test
    @DisplayName("fillEffect honours the clip")
    void fillEffectHonoursTheClip() {
        PixelBuffer target = effectCanvas();
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        graphics.setColor(new Color(0x80FF0000, true));
        graphics.setClip(0, 0, 3, 6);   // excludes column 3 onward
        graphics.fillEffect(1f, 1f, 3f, 1f);

        assertThat(misplaced(target, new Rectangle(2, 2, 1, 2), 0xF8870007, FILL), is(empty()));
    }

    /**
     * A 10x6 canvas holding {@link #FILL}, for a bar to be laid over.
     *
     * @return the canvas
     */
    private static PixelBuffer effectCanvas() {
        PixelBuffer canvas = PixelBuffer.canvas(10, 6);
        canvas.fill(FILL_STRAIGHT);
        return canvas;
    }

}
