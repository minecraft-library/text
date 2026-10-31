package lib.minecraft.text.font;

import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link MinecraftGlyphVector} {@code GlyphVector}-subclass contract: honest codes/positions/font,
 * stubbed outline-and-mutator surface, and the single-walk ownership that keeps measure, layout, and
 * draw agreeing. See the class javadoc of {@link MinecraftGlyphVector} for why the outline surface is
 * dead code and why no real-AWT interop test is possible (foreign vectors are reduced to glyph codes).
 */
@DisplayName("MinecraftGlyphVector honours the GlyphVector contract where our pipeline reads it")
class MinecraftGlyphVectorExtensionTest {

    /**
     * The bounds of a run that draws nothing.
     */
    private static final Rectangle2D EMPTY = new Rectangle2D.Double();

    private static String cp(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    private static Font awtFontFromFixture() throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT,
            new ByteArrayInputStream(ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF))).deriveFont(16.0f);
    }

    @Test
    @DisplayName("it is a java.awt.font.GlyphVector")
    void isAGlyphVector() {
        assertTrue(GlyphVector.class.isAssignableFrom(MinecraftGlyphVector.class));
        assertTrue(MinecraftFont.Vanilla.REGULAR.layout("Hi") instanceof GlyphVector);
    }

    @Test
    @DisplayName("getGlyphCode: a colour raster glyph reports its sidecar gid; a vanilla glyph resolves its cmap gid")
    void glyphCodesAreRealGidsForBothKinds() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector rasterVector = colour.layout(cp(ColorFontFixtures.CP_FLAT));
        assertThat("raster gid straight from the row", rasterVector.getGlyphCode(0), is(ColorFontFixtures.GID_FLAT));

        // A mono fallback glyph in a colour run resolves through the merged font's cmap (0/.notdef here).
        MinecraftGlyphVector monoInColour = colour.layout("A");
        assertThat(monoInColour.getGlyphCode(0), is(colour.glyphCode('A')));

        MinecraftFont.Vanilla vanilla = MinecraftFont.Vanilla.REGULAR;
        MinecraftGlyphVector vanillaVector = vanilla.layout("A");
        int cmapGid = vanilla.getActual().createGlyphVector(vanilla.fontRenderContext(), "A").getGlyphCode(0);
        assertThat(vanillaVector.getGlyphCode(0), is(cmapGid));
        assertThat("the vanilla font has a real glyph for 'A'", vanillaVector.getGlyphCode(0), greaterThan(0));
    }

    @Test
    @DisplayName("getGlyphCodes bulk-fills the same real gids as getGlyphCode")
    void glyphCodesBulkMatchesPerGlyph() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        int[] bulk = vector.getGlyphCodes(0, vector.getNumGlyphs(), null);
        assertThat(bulk.length, is(3));
        for (int i = 0; i < 3; i++) assertThat(bulk[i], is(vector.getGlyphCode(i)));
        assertThat(bulk[0], is(ColorFontFixtures.GID_FLAT));
    }

    @Test
    @DisplayName("getGlyphPosition/getGlyphPositions report the walk pen positions; the end index is the total advance")
    void positionsMatchWalkPen() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        for (int i = 0; i < vector.getNumGlyphs(); i++) {
            assertThat(vector.getGlyphPosition(i).getX(), is(vector.positionedGlyph(i).penX()));
            assertThat(vector.getGlyphPosition(i).getY(), is(0.0));
        }
        // The end position (index == numGlyphs) is the run's total advance.
        assertThat(vector.getGlyphPosition(3).getX(), closeTo(vector.advanceX(), 1e-9));

        float[] bulk = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        assertThat(bulk.length, is(8));
        assertThat((double) bulk[0], is(0.0));
        assertThat((double) bulk[2], is(16.0));
        assertThat((double) bulk[4], is(32.0));
        assertThat((double) bulk[6], closeTo(vector.advanceX(), 1e-6));   // end pen
        assertThat((double) bulk[1], is(0.0));   // y is always 0
    }

    @Test
    @DisplayName("getFont is honest for both kinds: vanilla hands back its own font, a colour font its merged ttf")
    void getFontHonestForBothKinds() {
        MinecraftFont.Vanilla vanilla = MinecraftFont.Vanilla.REGULAR;
        assertThat(vanilla.layout("Hi").getFont(), sameInstance(vanilla.getActual()));

        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        MinecraftFont.Color alt = ColorFontFixtures.altFont();
        Font colourFont = demo.layout(cp(ColorFontFixtures.CP_FLAT)).getFont();
        assertThat(colourFont, notNullValue());
        assertThat("gids match the merged font it hands back", colourFont.getNumGlyphs(), is(ColorFontFixtures.NUM_GLYPHS));
        // Same backing .ttf bytes -> one shared AWT font across every font id of the pack.
        assertThat(alt.layout(cp(ColorFontFixtures.CP_FLAT)).getFont(), sameInstance(colourFont));
    }

    @Test
    @DisplayName("getFontRenderContext, getNumGlyphs, and getGlyphTransform report real, legal data")
    void miscHonestSurface() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT) + "A");

        assertThat(vector.getFontRenderContext(), sameInstance(colour.fontRenderContext()));
        assertThat(vector.getNumGlyphs(), is(2));
        assertThat("no per-glyph transform is the legal null answer", vector.getGlyphTransform(0), nullValue());
    }

    @Test
    @DisplayName("getLogicalBounds spans the total advance and the font ascent/descent")
    void logicalBoundsFromMetrics() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftFontMetrics metrics = colour.metrics();

        Rectangle2D logical = vector.getLogicalBounds();
        assertThat(logical.getX(), is(0.0));
        assertThat(logical.getWidth(), closeTo(vector.advanceX(), 1e-9));
        assertThat(logical.getY(), closeTo(-metrics.getAscent(), 1e-9));
        assertThat(logical.getHeight(), closeTo(metrics.getAscent() + metrics.getDescent(), 1e-9));
    }

    @Test
    @DisplayName("getVisualBounds is the union of the pixels each glyph draws; a glyph drawing none contributes nothing")
    void visualBoundsFromInk() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();

        // A single 8x8 flat raster glyph at origin -> ink box (0,0,8,8).
        Rectangle2D flat = colour.layout(cp(ColorFontFixtures.CP_FLAT)).getVisualBounds();
        assertThat(flat.getX(), is(0.0));
        assertThat(flat.getY(), is(0.0));
        assertThat(flat.getWidth(), is(8.0));
        assertThat(flat.getHeight(), is(8.0));

        // A lone space provider has no ink -> empty visual bounds.
        Rectangle2D spaceOnly = colour.layout(cp(ColorFontFixtures.CP_NEG_SPACE)).getVisualBounds();
        assertThat(spaceOnly.getWidth(), is(0.0));
        assertThat(spaceOnly.getHeight(), is(0.0));

        // A raster glyph on a half-pixel pen lands where drawString rounds it, half up. A vanilla space is a
        // 1x1 transparent bitmap, and an italic bitmap leaves clear columns either side.
        String halfPen = "A" + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftFont.Vanilla regular = MinecraftFont.Vanilla.REGULAR;
        MinecraftFont.Vanilla italic = MinecraftFont.Vanilla.ITALIC;
        assertAll("runs answer the pixels drawString lays down",
            () -> assertThat("half pen", bounds(colour.layout(halfPen).getVisualBounds()), is(drawnBounds(colour, halfPen, Color.WHITE))),
            () -> assertThat("' '", bounds(regular.layout(" ").getVisualBounds()), is(EMPTY)),
            () -> assertThat("'   '", bounds(regular.layout("   ").getVisualBounds()), is(EMPTY)),
            () -> assertThat("'a'", bounds(regular.layout("a").getVisualBounds()), is(rect(0, -10, 10, 10))),
            () -> assertThat("'a '", bounds(regular.layout("a ").getVisualBounds()), is(rect(0, -10, 10, 10))),
            () -> assertThat("' a'", bounds(regular.layout(" a").getVisualBounds()), is(rect(8, -10, 10, 10))),
            () -> assertThat("'Hello World '", bounds(regular.layout("Hello World ").getVisualBounds()), is(rect(0, -14, 108, 14))),
            () -> assertThat("italic 'f'", bounds(italic.layout("f").getVisualBounds()), is(rect(1, -14, 9, 14))),
            () -> assertThat("italic 'gpq'", bounds(italic.layout("gpq").getVisualBounds()), is(rect(-2, -10, 37, 12)))
        );
    }

    @Test
    @DisplayName("getVisualBounds of every printable ASCII run in the four vanilla styles is the bounds drawString paints in any colour the text shader keeps, and a colour it discards paints nothing")
    void visualBoundsAreTheDrawnPixels() {
        StringBuilder printable = new StringBuilder();
        for (int c = 32; c <= 126; c++) printable.append((char) c);

        List<String> runs = new ArrayList<>();
        for (int i = 0; i < printable.length(); i++) {
            String c = printable.substring(i, i + 1);
            runs.add(c);
            runs.add(c + " ");
            runs.add(" " + c);
        }
        runs.add(printable.toString());

        MinecraftFont.Vanilla[] styles = {
            MinecraftFont.Vanilla.REGULAR, MinecraftFont.Vanilla.BOLD,
            MinecraftFont.Vanilla.ITALIC, MinecraftFont.Vanilla.BOLD_ITALIC
        };
        // A vanilla glyph's texels are opaque or clear, so the faintest colour the client lays draws every one
        // and the strongest it discards draws none.
        Color faintestLaid = new Color(0x1AFFFFFF, true);
        Color strongestDiscarded = new Color(0x19FFFFFF, true);
        List<String> mismatches = new ArrayList<>();
        List<String> undiscarded = new ArrayList<>();
        for (MinecraftFont.Vanilla style : styles) {
            for (String run : runs) {
                Rectangle2D visual = bounds(style.layout(run).getVisualBounds());
                Rectangle2D drawn = drawnBounds(style, run, faintestLaid);
                if (!visual.equals(drawn))
                    mismatches.add(style + " '" + run + "' visual " + visual + " drawn " + drawn);
                Rectangle2D discarded = drawnBounds(style, run, strongestDiscarded);
                if (!discarded.equals(EMPTY))
                    undiscarded.add(style + " '" + run + "' drawn " + discarded);
            }
        }

        int total = styles.length * runs.size();
        assertAll("every run against the pixels drawString lays",
            () -> assertTrue(mismatches.isEmpty(), mismatches.size() + " of " + total + " runs in 0x1AFFFFFF differ from the drawn pixels, first: "
                + mismatches.subList(0, Math.min(4, mismatches.size()))),
            () -> assertTrue(undiscarded.isEmpty(), undiscarded.size() + " of " + total + " runs in 0x19FFFFFF drew a pixel, first: "
                + undiscarded.subList(0, Math.min(4, undiscarded.size())))
        );
    }

    @Test
    @DisplayName("getGlyphMetrics is honest-and-cheap: advance from signedAdvance, bounds from the pixels the glyph draws")
    void glyphMetricsFromPackData() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_NEG_SPACE));

        assertThat((double) vector.getGlyphMetrics(0).getAdvanceX(), is(16.0));
        assertThat(vector.getGlyphMetrics(0).getBounds2D().getWidth(), is(8.0));
        assertThat(vector.getGlyphMetrics(0).getBounds2D().getHeight(), is(8.0));

        // A space glyph advances but has empty ink bounds.
        assertThat((double) vector.getGlyphMetrics(1).getAdvanceX(), is(-16.0));
        assertThat(vector.getGlyphMetrics(1).getBounds2D().getWidth(), is(0.0));

        // A vanilla space advances 4 mcPixels over a transparent bitmap, so it draws nothing; about its own
        // origin, italic f's clear column is not part of its bounds.
        GlyphMetrics vanillaSpace = MinecraftFont.Vanilla.REGULAR.layout(" ").getGlyphMetrics(0);
        GlyphMetrics italicF = MinecraftFont.Vanilla.ITALIC.layout("f").getGlyphMetrics(0);
        assertThat((double) vanillaSpace.getAdvanceX(), is(8.0));
        assertAll("vanilla glyph bounds are the pixels the glyph draws",
            () -> assertThat("' '", bounds(vanillaSpace.getBounds2D()), is(EMPTY)),
            () -> assertThat("italic 'f'", bounds(italicF.getBounds2D()), is(rect(1, -14, 9, 14)))
        );
    }

    private static @NotNull Rectangle2D rect(int x, int y, int width, int height) {
        return new Rectangle2D.Double(x, y, width, height);
    }

    /**
     * Copies a rectangle into a {@link Rectangle2D.Double}, so bounds of any subtype compare and print alike.
     */
    private static @NotNull Rectangle2D bounds(@NotNull Rectangle2D rectangle) {
        return new Rectangle2D.Double(rectangle.getX(), rectangle.getY(), rectangle.getWidth(), rectangle.getHeight());
    }

    /**
     * Draws a run in a colour through {@link MinecraftGraphics#drawString(String, int, int)} at a zero origin onto
     * a clear canvas and answers the {@link PixelBuffer#opaqueBounds() non-transparent bounds} about that origin,
     * or {@link #EMPTY} when nothing is drawn.
     */
    private static @NotNull Rectangle2D drawnBounds(@NotNull MinecraftFont font, @NotNull String text, @NotNull Color colour) {
        int margin = 64;
        int width = (int) Math.ceil(Math.abs(font.layout(text).advanceX())) + 2 * margin;
        PixelBuffer canvas = PixelBuffer.canvas(width, 2 * margin);
        MinecraftGraphics graphics = new MinecraftGraphics(canvas);
        graphics.setFont(font);
        graphics.setColor(colour);
        graphics.translate(margin, margin);
        graphics.drawString(text, 0, 0);

        Rectangle opaque = canvas.opaqueBounds();
        if (opaque.isEmpty()) return EMPTY;
        return rect(opaque.x - margin, opaque.y - margin, opaque.width, opaque.height);
    }

    @Test
    @DisplayName("the outline/justification surface throws UnsupportedOperationException (dead code we own the draw for)")
    void outlineSurfaceStubbed() {
        MinecraftGlyphVector vector = MinecraftFont.Vanilla.REGULAR.layout("A");

        assertThrows(UnsupportedOperationException.class, vector::getOutline);
        assertThrows(UnsupportedOperationException.class, () -> vector.getOutline(1f, 2f));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphOutline(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphLogicalBounds(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphVisualBounds(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphJustificationInfo(0));
    }

    @Test
    @DisplayName("the position/transform mutators throw: positions are pack data, not caller mutations")
    void mutatorsThrow() {
        MinecraftGlyphVector vector = MinecraftFont.Vanilla.REGULAR.layout("A");
        assertThrows(UnsupportedOperationException.class, () -> vector.setGlyphPosition(0, new Point2D.Float(5f, 5f)));
        assertThrows(UnsupportedOperationException.class, () -> vector.setGlyphTransform(0, new AffineTransform()));
    }

    @Test
    @DisplayName("performDefaultLayout is a no-op: precomputed pack pens are never recomputed")
    void performDefaultLayoutIsNoOp() {
        MinecraftGlyphVector vector = ColorFontFixtures.demoFont().layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));
        float[] before = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        vector.performDefaultLayout();
        float[] after = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        for (int i = 0; i < before.length; i++) assertThat(after[i], is(before[i]));
    }

    @Test
    @DisplayName("equals is value semantics over the font and the positioned glyph list")
    void equalsIsValueSemantics() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector a = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftGlyphVector b = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftGlyphVector c = colour.layout(cp(ColorFontFixtures.CP_FLAT));

        assertTrue(a.equals(b));
        assertTrue(a.equals(a));
        assertFalse(a.equals(c));
        assertFalse(a.equals((GlyphVector) MinecraftFont.Vanilla.REGULAR.layout("AA")));
    }

    @Test
    @DisplayName("drawGlyphVector rejects a foreign GlyphVector: AWT would reduce it to glyph codes and drop the pack layout")
    void drawGlyphVectorRejectsForeign() throws Exception {
        PixelBuffer target = PixelBuffer.canvas(16, 16);
        MinecraftGraphics graphics = new MinecraftGraphics(target);

        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        GlyphVector foreign = awtFontFromFixture().createGlyphVector(frc, "A");   // a real StandardGlyphVector

        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> graphics.drawGlyphVector(foreign, 0f, 0f));
        assertThat(ex.getMessage(), notNullValue());

        // Our own vector goes through fine.
        MinecraftFont.Vanilla.REGULAR.layout("A").paint(graphics, 0, 8, Color.WHITE);
    }

    @Test
    @DisplayName("one walk, three surfaces: metrics width, vector advance, and drawString ink all trace to a single walk")
    void oneWalkThreeSurfaces() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = "A" + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);   // mono + space + raster
        MinecraftGlyphVector vector = font.layout(text);

        // Surface 1 (measure) == Surface 2 (vector total advance).
        assertThat(font.metrics().stringWidth(text), is(Math.round((float) vector.advanceX())));

        // Surface 3 (draw): drawString and vector.paint are byte-identical, both consuming the one walk.
        PixelBuffer viaDraw = PixelBuffer.canvas(96, 48);
        viaDraw.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(viaDraw);
        g1.setFont(font);
        g1.setColor(Color.WHITE);
        g1.drawString(text, 1, 16);

        PixelBuffer viaPaint = PixelBuffer.canvas(96, 48);
        viaPaint.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(viaPaint);
        vector.paint(g2, 1, 16, Color.WHITE);

        for (int y = 0; y < 48; y++)
            for (int x = 0; x < 96; x++)
                assertThat("pixel (" + x + "," + y + ")", viaDraw.getPixel(x, y), is(viaPaint.getPixel(x, y)));

        // And the raster glyph's untinted top-left lands exactly at the buffer column the walk pen predicts.
        // That pen is A's advance plus the fixture's 1.5 px space, a tie the blit rounds half up.
        double rasterPen = vector.positionedGlyph(2).penX();
        assertThat("the raster pen sits on half an output pixel", rasterPen % 1.0, is(0.5));
        int cx = 1 * MinecraftFont.MC_PIXEL_SCALE;
        int rasterCol = cx + (int) Math.round(rasterPen);
        int strikeTop = -1;
        for (int y = 0; y < 48 && strikeTop < 0; y++)
            for (int x = 0; x < 96; x++)
                if (viaDraw.getPixel(x, y) == 0xFFDC2828) {
                    strikeTop = y;
                    break;
                }
        assertTrue(strikeTop >= 0, "the raster glyph painted its strike");
        assertThat("the strike's top-left lands at the walk-predicted column " + rasterCol,
            viaDraw.getPixel(rasterCol, strikeTop), is(0xFFDC2828));
        assertThat("nothing left of the strike's top-left", viaDraw.getPixel(rasterCol - 1, strikeTop), is(0));
    }

}
