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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * font the client draws a run in, the client's missing font included, and each font's
 * {@link MinecraftFont#slant()} to the one its font file declares.
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
    @DisplayName("a glyph whose bounds start off the pixel grid keeps its first and last column and row: an italic '(' and '4' and a regular U+02C6 and U+02D7 draw exactly the pixels their outline covers")
    void aGlyphOffThePixelGridKeepsItsLastColumnAndRow() {
        List<Map.Entry<MinecraftFont.Vanilla, Integer>> subjects = List.of(
            Map.entry(MinecraftFont.Vanilla.ITALIC, (int) '('),
            Map.entry(MinecraftFont.Vanilla.ITALIC, (int) '4'),
            Map.entry(MinecraftFont.Vanilla.REGULAR, 0x02C6),
            Map.entry(MinecraftFont.Vanilla.REGULAR, 0x02D7)
        );
        List<Executable> checks = new ArrayList<>();

        for (Map.Entry<MinecraftFont.Vanilla, Integer> subject : subjects) {
            MinecraftFont.Vanilla font = subject.getKey();
            int codepoint = subject.getValue();
            MinecraftGlyph glyph = font.glyph(codepoint);
            Shape outline = font.monoOutline(codepoint, 0).orElseThrow();
            String label = String.format("%s U+%04X", font, codepoint);
            checks.add(() -> assertThat(label, offTheOutline(glyph, outline, false), is(empty())));
        }

        assertAll(checks);
    }

    @Test
    @DisplayName("every printable ASCII glyph of every vanilla face lights each pixel its outline covers")
    void everyAsciiGlyphLightsEachPixelItsOutlineCovers() {
        List<Executable> checks = new ArrayList<>();

        for (MinecraftFont.Vanilla font : MinecraftFont.Vanilla.values()) {
            for (int codepoint = ' '; codepoint <= '~'; codepoint++) {
                MinecraftGlyph glyph = font.glyph(codepoint);
                Shape outline = font.monoOutline(codepoint, 0).orElseThrow();
                String label = String.format("%s '%c'", font, codepoint);
                checks.add(() -> assertThat(label, offTheOutline(glyph, outline, true), is(empty())));
            }
        }

        assertAll(checks);
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
    @DisplayName("the italic faces answer the slant their files declare: a quarter mcPixel right per mcPixel of height, about the height 3 mcPixels above the baseline")
    void theItalicFacesAnswerTheSlantTheirFilesDeclare() {
        List<Executable> checks = new ArrayList<>();

        for (MinecraftFont.Vanilla font : List.of(MinecraftFont.Vanilla.ITALIC, MinecraftFont.Vanilla.BOLD_ITALIC)) {
            MinecraftFont.Slant slant = font.slant();
            checks.add(() -> assertThat(font + " slant", slant, is(new MinecraftFont.Slant(0.25f, 3f))));
            checks.add(() -> assertThat(font + " at the pivot", slant.shift(3f), is(0f)));
            checks.add(() -> assertThat(font + " on the baseline, the caret offset of -96 units at 128 units an mcPixel", slant.shift(0f), is(-0.75f)));
            checks.add(() -> assertThat(font + " on the ascent line", slant.shift(7f), is(1f)));
        }

        assertAll(checks);
    }

    @Test
    @DisplayName("a font whose file declares no slant answers none: the upright faces, the alternate scripts, a colour font over a file declaring none, and the missing font")
    void aFontWhoseFileDeclaresNoSlantAnswersNone() throws IOException {
        List<MinecraftFont> fonts = List.of(
            MinecraftFont.Vanilla.REGULAR,
            MinecraftFont.Vanilla.BOLD,
            MinecraftFont.Vanilla.GALACTIC,
            MinecraftFont.Vanilla.ILLAGERALT,
            colourFontUnder(FontId.parse("pack:font")),
            MinecraftFont.Missing.INSTANCE
        );
        List<Executable> checks = new ArrayList<>();

        for (MinecraftFont font : fonts)
            checks.add(() -> assertThat(font.fontId().toString(), font.slant(), is(MinecraftFont.Slant.NONE)));

        checks.add(() -> assertThat("no slant moves no point", MinecraftFont.Slant.NONE.shift(7f), is(0f)));
        assertAll(checks);
    }

    @Test
    @DisplayName("a font answers the slant its own file declares, at its own units per em, and none where the file holds no hhea table")
    void aFontAnswersTheSlantItsOwnFileDeclares() throws IOException {
        byte[] slanted = synthColour();
        ByteBuffer buffer = ByteBuffer.wrap(slanted);
        int hhea = buffer.getInt(tableRecord(slanted, "hhea") + 8);
        int head = buffer.getInt(tableRecord(slanted, "head") + 8);
        buffer.putShort(hhea + 18, (short) 2).putShort(hhea + 20, (short) 1).putShort(hhea + 22, (short) -128);
        buffer.putShort(head + 18, (short) 2048);

        byte[] noHhea = slanted.clone();
        noHhea[tableRecord(noHhea, "hhea") + 3] = 'x';

        MinecraftFont.Slant slant = colourFontOver(FontId.parse("pack:slanted"), slanted).slant();
        MinecraftFont.Slant none = colourFontOver(FontId.parse("pack:unslanted"), noHhea).slant();

        assertAll(
            () -> assertThat("caret rise 2, run 1, offset -128 at 2048 units per em", slant, is(new MinecraftFont.Slant(0.5f, 1f))),
            () -> assertThat("on the baseline, the caret offset of -128 units at 256 units an mcPixel", slant.shift(0f), is(-0.5f)),
            () -> assertThat("the same caret under a table tag other than hhea", none, is(MinecraftFont.Slant.NONE))
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
     * Lists every pixel about the pen and the baseline where a glyph's bitmap, laid at its bearing,
     * disagrees with the pixel centres its outline covers.
     *
     * @param glyph the glyph under test
     * @param outline the glyph's outline at the same pen and baseline
     * @param unlitOnly whether to list only the covered pixels the bitmap leaves unlit, and not the lit
     * ones the outline does not cover
     * @return each disagreeing pixel as its position and which side lights it
     */
    private static @NotNull List<String> offTheOutline(@NotNull MinecraftGlyph glyph, @NotNull Shape outline, boolean unlitOnly) {
        PixelBuffer bitmap = glyph.bitmap();
        Rectangle drawn = new Rectangle(glyph.bearingX(), glyph.bearingY(), bitmap.width(), bitmap.height());
        Rectangle area = outline.getBounds().union(drawn);
        area.grow(1, 1);
        List<String> wrong = new ArrayList<>();

        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                boolean covered = outline.contains(x + 0.5, y + 0.5);
                boolean lit = drawn.contains(x, y) && bitmap.getPixel(x - drawn.x, y - drawn.y) >>> 24 != 0;
                if (covered && !lit)
                    wrong.add(String.format("(%d,%d) unlit", x, y));
                else if (lit && !covered && !unlitOnly)
                    wrong.add(String.format("(%d,%d) lit outside the outline", x, y));
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
        return colourFontOver(fontId, synthColour());
    }

    /**
     * Builds a colour font holding no row of its own under a font id, over the given font file and
     * {@link MinecraftFont.Vanilla#BOLD}.
     *
     * @param fontId the font id
     * @param ttf the colour font file's bytes
     * @return the colour font
     * @throws IOException if the sidecar cannot be read
     */
    private static @NotNull MinecraftFont colourFontOver(@NotNull FontId fontId, byte @NotNull [] ttf) throws IOException {
        ColorGlyphSidecar sidecar = ColorGlyphSidecar.parse(new StringReader("{\"schema_version\": 2, \"glyphs\": []}"));
        return MinecraftFont.Color.of(fontId, ttf, sidecar, MinecraftFont.Vanilla.BOLD);
    }

    /**
     * Reads the colour fixture font {@code SynthColour.ttf}, whose {@code hhea} caret stands upright.
     *
     * @return a fresh copy of the file's bytes
     * @throws IOException if the fixture font cannot be read
     */
    private static byte @NotNull [] synthColour() throws IOException {
        try (InputStream ttf = MinecraftFontTest.class.getClassLoader().getResourceAsStream("colorfont/SynthColour.ttf")) {
            assertThat("the colour fixture font is on the test classpath", ttf, notNullValue());
            return ttf.readAllBytes();
        }
    }

    /**
     * Finds a table's record in an sfnt font file's table directory: a {@code uint16} table count at
     * byte 4, then a 16-byte record per table from byte 12, each its four-character tag and, 8 bytes
     * in, the table's offset from the start of the file.
     *
     * @param sfnt the raw font bytes
     * @param tag the table's four-character tag
     * @return where the table's record starts
     */
    private static int tableRecord(byte @NotNull [] sfnt, @NotNull String tag) {
        int numTables = Short.toUnsignedInt(ByteBuffer.wrap(sfnt).getShort(4));

        for (int i = 0, record = 12; i < numTables; i++, record += 16) {
            if (tag.equals(new String(sfnt, record, 4, StandardCharsets.ISO_8859_1)))
                return record;
        }

        throw new AssertionError("The fixture font has no '" + tag + "' table");
    }

}
