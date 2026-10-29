package lib.minecraft.renderer.text.font;

import dev.simplified.image.PixelBuffer;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.ColorGlyphSidecar;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGlyph;
import lib.minecraft.text.font.MinecraftGlyphVector;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.GlyphMetrics;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Smoke coverage for {@link MinecraftFont}. Forces every classpath-backed enum value to load
 * its underlying {@code .otf} file, catching any mismatch between the enum's declared
 * filenames and what the {@code generateFonts} Gradle task actually produces, and holds
 * {@link MinecraftFont#of(FontId, MinecraftFont.Style)} and {@link TextSegment#resolveFont()} to the
 * font the client draws a run in, the client's missing font included.
 */
@DisplayName("MinecraftFont resolves every .otf from the classpath")
class MinecraftFontTest {

    private static final FontId ALT = FontId.parse("minecraft:alt");

    private static final FontId ILLAGERALT = FontId.parse("minecraft:illageralt");

    /** The id the library gives the default font's bold member, which names no font of the client's. */
    private static final FontId DEFAULT_BOLD = FontId.parse("minecraft:default/bold");

    /** The missing glyph's pixels about the pen and the baseline, in output pixels. */
    private static final Rectangle BOX = new Rectangle(0, -14, 10, 16);

    /** The clear pixels inside the missing glyph's edges, about the pen and the baseline. */
    private static final Rectangle HOLLOW = new Rectangle(2, -12, 6, 12);

    @Test
    @DisplayName("REGULAR loads Minecraft-Regular.otf")
    void regularResolves() {
        assertThat(MinecraftFont.Vanilla.REGULAR.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.REGULAR.getStyle(), equalTo(MinecraftFont.Style.REGULAR));;
    }

    @Test
    @DisplayName("BOLD loads Minecraft-Bold.otf")
    void boldResolves() {
        assertThat(MinecraftFont.Vanilla.BOLD.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.BOLD.getStyle(), equalTo(MinecraftFont.Style.BOLD));
    }

    @Test
    @DisplayName("ITALIC loads Minecraft-Italic.otf")
    void italicResolves() {
        assertThat(MinecraftFont.Vanilla.ITALIC.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.ITALIC.getStyle(), equalTo(MinecraftFont.Style.ITALIC));
    }

    @Test
    @DisplayName("BOLD_ITALIC loads Minecraft-BoldItalic.otf")
    void boldItalicResolves() {
        assertThat(MinecraftFont.Vanilla.BOLD_ITALIC.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.BOLD_ITALIC.getStyle(), equalTo(MinecraftFont.Style.BOLD_ITALIC));
    }

    @Test
    @DisplayName("GALACTIC loads Minecraft-Galactic.otf (Standard Galactic Alphabet)")
    void galacticResolves() {
        assertThat(MinecraftFont.Vanilla.GALACTIC.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.GALACTIC.getStyle(), equalTo(MinecraftFont.Style.GALACTIC));
    }

    @Test
    @DisplayName("ILLAGERALT loads Minecraft-Illageralt.otf")
    void illageraltResolves() {
        assertThat(MinecraftFont.Vanilla.ILLAGERALT.getActual(), notNullValue());
        assertThat(MinecraftFont.Vanilla.ILLAGERALT.getStyle(), equalTo(MinecraftFont.Style.ILLAGERALT));
    }

    @Test
    @DisplayName("of(style) returns the matching enum value for typographical styles")
    void ofStyleReturnsMatchingTypographicStyles() {
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.REGULAR), equalTo(MinecraftFont.Vanilla.REGULAR));
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.BOLD), equalTo(MinecraftFont.Vanilla.BOLD));
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.ITALIC), equalTo(MinecraftFont.Vanilla.ITALIC));
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.BOLD_ITALIC), equalTo(MinecraftFont.Vanilla.BOLD_ITALIC));
    }

    @Test
    @DisplayName("of(style) returns the matching enum value for alternate-script styles")
    void ofStyleReturnsMatchingScriptStyles() {
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.GALACTIC), equalTo(MinecraftFont.Vanilla.GALACTIC));
        assertThat(MinecraftFont.Vanilla.of(MinecraftFont.Style.ILLAGERALT), equalTo(MinecraftFont.Vanilla.ILLAGERALT));
    }

    @Test
    @DisplayName("Style.of(int) round-trips awt style ints")
    void styleOfIntRoundTripsAwtIds() {
        assertThat(MinecraftFont.Style.of(0), equalTo(MinecraftFont.Style.REGULAR));
        assertThat(MinecraftFont.Style.of(1), equalTo(MinecraftFont.Style.BOLD));
        assertThat(MinecraftFont.Style.of(2), equalTo(MinecraftFont.Style.ITALIC));
        assertThat(MinecraftFont.Style.of(3), equalTo(MinecraftFont.Style.BOLD_ITALIC));
        assertThat(MinecraftFont.Style.of(4), equalTo(MinecraftFont.Style.GALACTIC));
        assertThat(MinecraftFont.Style.of(5), equalTo(MinecraftFont.Style.ILLAGERALT));
    }

    @Test
    @DisplayName("Style.of(unknown) falls back to REGULAR")
    void styleOfUnknownFallsBackToRegular() {
        assertThat(MinecraftFont.Style.of(99), equalTo(MinecraftFont.Style.REGULAR));
        assertThat(MinecraftFont.Style.of(-1), equalTo(MinecraftFont.Style.REGULAR));
    }

    @Test
    @DisplayName("getPath() returns a real file ending in the expected .otf name")
    void pathIsSet() {
        assertThat(MinecraftFont.Vanilla.REGULAR.getPath(), notNullValue());
        assertThat(Files.isRegularFile(MinecraftFont.Vanilla.REGULAR.getPath()), is(true));
        assertThat(MinecraftFont.Vanilla.REGULAR.getPath().toString(), endsWith("Minecraft-Regular.otf"));
    }

    @Test
    @DisplayName("of(minecraft:default, style) answers the default font's member for the style's bold and italic")
    void ofDefaultAnswersTheMemberForTheStyle() {
        assertAll(
            () -> assertThat("the client's FontDescription.DEFAULT", FontId.DEFAULT, is(FontId.parse("minecraft:default"))),
            () -> assertThat("the id component JSON carries", MinecraftFont.of(FontId.parse("minecraft:default"), MinecraftFont.Style.BOLD), is(MinecraftFont.Vanilla.BOLD)),
            () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.REGULAR), is(MinecraftFont.Vanilla.REGULAR)),
            () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.BOLD), is(MinecraftFont.Vanilla.BOLD)),
            () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.ITALIC), is(MinecraftFont.Vanilla.ITALIC)),
            () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.BOLD_ITALIC), is(MinecraftFont.Vanilla.BOLD_ITALIC)),
            () -> assertThat("a script style carries neither", MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.GALACTIC), is(MinecraftFont.Vanilla.REGULAR))
        );
    }

    @Test
    @DisplayName("of answers an alternate script in its one face, whatever the style")
    void ofAnswersAScriptInItsOneFace() {
        assertAll(
            () -> assertThat(MinecraftFont.of(ALT, MinecraftFont.Style.REGULAR), is(MinecraftFont.Vanilla.GALACTIC)),
            () -> assertThat(MinecraftFont.of(ALT, MinecraftFont.Style.BOLD), is(MinecraftFont.Vanilla.GALACTIC)),
            () -> assertThat(MinecraftFont.of(ILLAGERALT, MinecraftFont.Style.REGULAR), is(MinecraftFont.Vanilla.ILLAGERALT)),
            () -> assertThat(MinecraftFont.of(ILLAGERALT, MinecraftFont.Style.BOLD_ITALIC), is(MinecraftFont.Vanilla.ILLAGERALT))
        );
    }

    @Test
    @DisplayName("a font registered under a member's id answers for minecraft:default in its style until it is unregistered")
    void ofDefaultReadsTheMemberThroughTheRegistry() throws IOException {
        MinecraftFont font = colourFontUnder(DEFAULT_BOLD);
        MinecraftFont.register(font);

        try {
            assertAll(
                () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.BOLD), is(sameInstance(font))),
                () -> assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.REGULAR), is(MinecraftFont.Vanilla.REGULAR)),
                () -> assertThat("get reads the registration", MinecraftFont.get(DEFAULT_BOLD).orElseThrow(), is(sameInstance(font))),
                () -> assertThat("the member's id names no font of the client's", MinecraftFont.of(DEFAULT_BOLD, MinecraftFont.Style.BOLD), is(MinecraftFont.Missing.INSTANCE))
            );
        } finally {
            MinecraftFont.unregister(DEFAULT_BOLD);
        }

        assertThat(MinecraftFont.of(FontId.DEFAULT, MinecraftFont.Style.BOLD), is(MinecraftFont.Vanilla.BOLD));
    }

    @Test
    @DisplayName("a font registered under any other id answers for it in its one face until it is unregistered, in a script's place too")
    void ofAnswersARegisteredFontInItsOneFace() throws IOException {
        FontId packId = FontId.parse("pack:font");
        MinecraftFont pack = colourFontUnder(packId);
        MinecraftFont alt = colourFontUnder(ALT);
        MinecraftFont.register(pack);
        MinecraftFont.register(alt);

        try {
            assertAll(
                () -> assertThat(MinecraftFont.of(packId, MinecraftFont.Style.REGULAR), is(sameInstance(pack))),
                () -> assertThat(MinecraftFont.of(packId, MinecraftFont.Style.BOLD), is(sameInstance(pack))),
                () -> assertThat("in minecraft:alt's place", MinecraftFont.of(ALT, MinecraftFont.Style.BOLD), is(sameInstance(alt)))
            );
        } finally {
            MinecraftFont.unregister(packId);
            MinecraftFont.unregister(ALT);
        }

        assertAll(
            () -> assertThat(MinecraftFont.of(packId, MinecraftFont.Style.REGULAR), is(MinecraftFont.Missing.INSTANCE)),
            () -> assertThat(MinecraftFont.of(packId, MinecraftFont.Style.BOLD), is(MinecraftFont.Missing.INSTANCE)),
            () -> assertThat(MinecraftFont.of(ALT, MinecraftFont.Style.BOLD), is(MinecraftFont.Vanilla.GALACTIC))
        );
    }

    @Test
    @DisplayName("of answers the missing font for an id the library draws no font for, and get keeps its answers")
    void ofAnswersTheMissingFontForAnIdTheLibraryDrawsNoFontFor() {
        List<String> ids = List.of(
            "minecraft:default/bold", "minecraft:default/italic", "minecraft:default/bold_italic",
            "minecraft:uniform", "minecraft:include/default", "minecraft:include/space", "minecraft:include/unifont",
            "minecraft:nothing", "pack:unregistered"
        );
        List<Executable> checks = new ArrayList<>();

        for (String id : ids) {
            for (MinecraftFont.Style style : MinecraftFont.Style.values())
                checks.add(() -> assertThat(id + " " + style, MinecraftFont.of(FontId.parse(id), style), is(MinecraftFont.Missing.INSTANCE)));
        }

        checks.add(() -> assertThat(MinecraftFont.get(DEFAULT_BOLD).orElseThrow(), is(MinecraftFont.Vanilla.BOLD)));
        checks.add(() -> assertThat(MinecraftFont.get(FontId.parse("minecraft:uniform")).isPresent(), is(false)));
        assertAll(checks);
    }

    @Test
    @DisplayName("the missing font draws the client's missing glyph for every codepoint: a hollow 5 x 8 box at the ascent line, advancing 6")
    void theMissingGlyphIsTheClientsHollowBox() {
        MinecraftFont missing = MinecraftFont.Missing.INSTANCE;
        List<Executable> checks = new ArrayList<>();

        for (int codepoint : new int[] {'a', ' ', 0x1F600}) {
            String text = new String(Character.toChars(codepoint));
            String label = String.format("U+%04X ", codepoint);
            MinecraftGlyph glyph = missing.glyph(codepoint);
            MinecraftGlyphVector vector = missing.layout(text);
            GlyphMetrics metrics = vector.getGlyphMetrics(0);

            PixelBuffer canvas = PixelBuffer.create(12, 16);
            MinecraftGraphics graphics = new MinecraftGraphics(canvas);
            graphics.setFont(missing);
            graphics.setColor(Color.WHITE);
            graphics.drawString(text, 0, 7);

            checks.add(() -> assertThat(label + "kind", glyph.kind(), is(MinecraftGlyphVector.Kind.MONO)));
            checks.add(() -> assertThat(label + "advance", glyph.signedAdvance(), is(12f)));
            checks.add(() -> assertThat(label + "bearing", List.of(glyph.bearingX(), glyph.bearingY()), is(List.of(0, -14))));
            checks.add(() -> assertThat(label + "texels", offTheBox(glyph.bitmap()), is(empty())));
            checks.add(() -> assertThat(label + "visual bounds", vector.getVisualBounds(), is(BOX)));
            checks.add(() -> assertThat(label + "glyph metrics advance", metrics.getAdvance(), is(12f)));
            checks.add(() -> assertThat(label + "glyph metrics bounds", metrics.getBounds2D().getBounds(), is(BOX)));
            checks.add(() -> assertThat(label + "mcPixel advance", missing.metrics().stringAdvanceMcPixels(text), is(6f)));
            checks.add(() -> assertThat(label + "drawn on a baseline 7 mcPixels down", offTheBox(canvas), is(empty())));
        }

        checks.add(() -> assertThat("'il' in whole mcPixels", missing.metrics().stringWidthMcPixels("il"), is(12)));
        assertAll(checks);
    }

    @Test
    @DisplayName("the missing glyph's outline is the box it draws, at the pen")
    void theMissingGlyphsOutlineIsTheBoxItDraws() {
        Shape outline = MinecraftFont.Missing.INSTANCE.monoOutline('a', 24).orElseThrow();
        List<String> wrong = new ArrayList<>();

        for (int y = -16; y < 4; y++) {
            for (int x = -2; x < 12; x++) {
                boolean lit = BOX.contains(x, y) && !HOLLOW.contains(x, y);
                if (outline.contains(24 + x + 0.5, y + 0.5) != lit)
                    wrong.add(String.format("(%d,%d) %s", x, y, lit ? "outside" : "inside"));
            }
        }

        assertThat(wrong, is(empty()));
        assertThat("the knockout hook reads it", MinecraftFont.Missing.INSTANCE.layout("a").outline(0).isPresent(), is(true));
    }

    @Test
    @DisplayName("the missing font has no backing AWT font: glyph code 0 for every codepoint, getFont throws, and the regular font's line geometry")
    void theMissingFontHasNoBackingAwtFont() {
        MinecraftFont missing = MinecraftFont.Missing.INSTANCE;
        MinecraftGlyphVector vector = missing.layout("a" + new String(Character.toChars(0x1F600)));
        double ascent = MinecraftFont.Vanilla.REGULAR.metrics().getAscent();
        double descent = MinecraftFont.Vanilla.REGULAR.metrics().getDescent();

        assertAll(
            () -> assertThat(missing.glyphCode('a'), is(0)),
            () -> assertThat(vector.getGlyphCodes(0, 2, null), is(new int[] {0, 0})),
            () -> assertThrows(UnsupportedOperationException.class, missing::awtFont),
            () -> assertThrows(UnsupportedOperationException.class, vector::getFont),
            () -> assertThat(vector.getFontRenderContext().getTransform().isIdentity(), is(true)),
            () -> assertThat(vector.getFontRenderContext().isAntiAliased(), is(false)),
            () -> assertThat(vector.getFontRenderContext().usesFractionalMetrics(), is(false)),
            () -> assertThat(missing.metrics().getAscent(), is(MinecraftFont.Vanilla.REGULAR.metrics().getAscent())),
            () -> assertThat(missing.metrics().getDescent(), is(MinecraftFont.Vanilla.REGULAR.metrics().getDescent())),
            () -> assertThat(missing.metrics().getHeight(), is(MinecraftFont.Vanilla.REGULAR.metrics().getHeight())),
            () -> assertThat("logical bounds", missing.layout("a").getLogicalBounds(), is(new Rectangle2D.Double(0, -ascent, 12, ascent + descent))),
            () -> assertThat(missing.fontId(), is(FontId.parse("minecraft:missing")))
        );
    }

    @Test
    @DisplayName("resolveFont answers the default font's member for a segment naming no font")
    void resolveFontDrawsAnUnnamedSegmentInTheDefaultFont() {
        TextSegment plain = TextSegment.builder().text("text").build();

        assertAll(
            () -> assertThat(plain.resolveFont(), is(MinecraftFont.Vanilla.REGULAR)),
            () -> assertThat(plain.mutate().bold(true).build().resolveFont(), is(MinecraftFont.Vanilla.BOLD)),
            () -> assertThat(plain.mutate().italic(true).build().resolveFont(), is(MinecraftFont.Vanilla.ITALIC)),
            () -> assertThat(plain.mutate().bold(true).italic(true).build().resolveFont(), is(MinecraftFont.Vanilla.BOLD_ITALIC))
        );
    }

    @Test
    @DisplayName("resolveFont answers the font a segment names, and the one a run inherits through toLines")
    void resolveFontDrawsASegmentInTheFontItNames() {
        TextSegment parent = TextSegment.builder()
            .text("Galactic ")
            .font(ALT)
            .extra(List.of(
                TextSegment.builder().text("inherits").bold(true).build(),
                TextSegment.fromJson("{\"text\": \"resets\", \"font\": \"minecraft:default\", \"bold\": true}")
            ))
            .build();
        List<TextSegment> runs = parent.toLines().getFirst().getSegments();

        assertAll(
            () -> assertThat(TextSegment.builder().text("Galactic").font(ALT).build().resolveFont(), is(MinecraftFont.Vanilla.GALACTIC)),
            () -> assertThat(TextSegment.builder().text("Galactic").font(ALT).bold(true).build().resolveFont(), is(MinecraftFont.Vanilla.GALACTIC)),
            () -> assertThat("a run inheriting minecraft:alt", runs.get(1).resolveFont(), is(MinecraftFont.Vanilla.GALACTIC)),
            () -> assertThat("a bold run naming minecraft:default under it", runs.getLast().resolveFont(), is(MinecraftFont.Vanilla.BOLD)),
            () -> assertThat(TextSegment.builder().text("u").font(FontId.parse("minecraft:uniform")).build().resolveFont(), is(MinecraftFont.Missing.INSTANCE))
        );
    }

    /**
     * Lists every pixel of a buffer that differs from the missing glyph laid in opaque white with its
     * top left at the buffer's origin: lit on the box's two-pixel edges, clear inside them and beyond
     * the box.
     *
     * @param buffer the buffer under test
     * @return each misplaced pixel as its position, its value and the value expected
     */
    private static @NotNull List<String> offTheBox(@NotNull PixelBuffer buffer) {
        List<String> wrong = new ArrayList<>();

        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                int baselineY = y + BOX.y;
                int expected = BOX.contains(x, baselineY) && !HOLLOW.contains(x, baselineY) ? 0xFFFFFFFF : 0;
                if (buffer.getPixel(x, y) != expected)
                    wrong.add(String.format("(%d,%d) %08X not %08X", x, y, buffer.getPixel(x, y), expected));
            }
        }

        return wrong;
    }

    /**
     * Builds a colour font holding no row of its own under a font id, over
     * {@link MinecraftFont.Vanilla#BOLD}.
     *
     * @param fontId the font id
     * @return the colour font
     * @throws IOException if the fixture font cannot be read
     */
    private static @NotNull MinecraftFont colourFontUnder(@NotNull FontId fontId) throws IOException {
        ColorGlyphSidecar sidecar = ColorGlyphSidecar.parse(new StringReader("{\"schema_version\": 2, \"glyphs\": []}"));

        try (InputStream ttf = MinecraftFontTest.class.getClassLoader().getResourceAsStream("colorfont/SynthColour.ttf")) {
            assertThat("the colour fixture font is on the test classpath", ttf, notNullValue());
            return MinecraftFont.Color.of(fontId, ttf.readAllBytes(), sidecar, MinecraftFont.Vanilla.BOLD);
        }
    }

}
