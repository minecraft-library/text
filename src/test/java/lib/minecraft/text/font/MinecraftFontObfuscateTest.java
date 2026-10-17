package lib.minecraft.text.font;

import lib.minecraft.text.font.MinecraftFont.Vanilla;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link MinecraftFont#obfuscate(int, RandomGenerator)} over the generated
 * fonts and the colour fixture. Every assertion is a property of the fonts' tables - an advance kept,
 * a draw counted, a candidate set - and never a pinned pick or member count, because both move with
 * the generated fonts and with the JDK's view of them.
 * <p>
 * "Advance" is {@code font.glyph(codepoint).signedAdvance()}, what layout moves the pen by.
 */
@DisplayName("MinecraftFont#obfuscate substitutes glyphs of the original's advance as the client does")
class MinecraftFontObfuscateTest {

    private static final int SEEDS = 64;

    /** A codepoint the generated fonts do not map. */
    private static final int UNMAPPED = 0x4E00;

    /**
     * Whitespace other than U+0020: a no-break space, which {@link Character#isWhitespace} rejects,
     * and an en space the fonts map and an ideographic space they do not, which it accepts.
     */
    private static final int[] OTHER_WHITESPACE = {0x00A0, 0x2002, 0x3000};

    /** The demo colour font's raster rows laid out at 16 px. */
    private static final List<Integer> DEMO_ROWS_AT_16_PX = List.of(
        ColorFontFixtures.CP_FLAT, ColorFontFixtures.CP_AA, ColorFontFixtures.CP_FLAT_DUP,
        ColorFontFixtures.CP_MID, ColorFontFixtures.CP_TALL
    );

    @Test
    @DisplayName("every substitute lays out at the original's advance")
    void substituteKeepsTheOriginalsAdvance() {
        for (Vanilla font : List.of(Vanilla.REGULAR, Vanilla.ITALIC, Vanilla.GALACTIC, Vanilla.ILLAGERALT)) {
            for (int codepoint : sample(font)) {
                float expected = advance(font, codepoint);
                for (long seed = 0; seed < SEEDS; seed++) {
                    long drawn = seed;
                    int pick = font.obfuscate(codepoint, new Random(seed));
                    assertEquals(expected, advance(font, pick), () -> describe(font, codepoint, drawn));
                }
            }
        }
    }

    @Test
    @DisplayName("the bold and italic fonts share REGULAR's table and draw REGULAR's picks at REGULAR's advance")
    void boldAndItalicKeyOnTheRegularAdvance() {
        for (Vanilla font : List.of(Vanilla.BOLD, Vanilla.ITALIC, Vanilla.BOLD_ITALIC)) {
            assertThat(font.glyphsByWidth(), is(sameInstance(Vanilla.REGULAR.glyphsByWidth())));

            for (int codepoint : sample(Vanilla.REGULAR)) {
                float expected = advance(Vanilla.REGULAR, codepoint);
                for (long seed = 0; seed < SEEDS; seed++) {
                    long drawn = seed;
                    int pick = font.obfuscate(codepoint, new Random(seed));
                    assertEquals(expected, advance(Vanilla.REGULAR, pick), () -> describe(font, codepoint, drawn));
                    assertEquals(Vanilla.REGULAR.obfuscate(codepoint, new Random(seed)), pick, () -> describe(font, codepoint, drawn));
                }
            }
        }
    }

    @Test
    @DisplayName("an obfuscated run lays out at the original run's width for every seed")
    void runKeepsItsWidth() {
        List<String> texts = List.of("Hello World", "AAAA", "\u00A7k test \u272A \u2764 \u4F60\u597D");

        for (Vanilla font : List.of(Vanilla.REGULAR, Vanilla.GALACTIC)) {
            for (String text : texts) {
                double expected = font.metrics().stringAdvanceX(text);
                for (long seed = 0; seed < SEEDS; seed++) {
                    String obfuscated = font.obfuscate(text, new Random(seed));
                    assertThat(font + " '" + text + "' seed " + seed, font.metrics().stringAdvanceX(obfuscated), is(expected));
                }
            }
        }
    }

    @Test
    @DisplayName("U+0020 is never substituted and costs no draw")
    void spaceIsKeptWithoutADraw() {
        String text = "a b  c d ";
        int[] original = text.codePoints().toArray();

        for (long seed = 0; seed < SEEDS; seed++) {
            int[] obfuscated = Vanilla.REGULAR.obfuscate(text, new Random(seed)).codePoints().toArray();
            for (int index = 0; index < original.length; index++) {
                if (original[index] == ' ')
                    assertThat("seed " + seed + " index " + index, obfuscated[index], is((int) ' '));
            }

            Random random = new Random(seed);
            assertThat(Vanilla.REGULAR.obfuscate(' ', random), is((int) ' '));
            assertThat(random.nextInt(), is(new Random(seed).nextInt()));
        }
    }

    @Test
    @DisplayName("whitespace other than U+0020 is substituted at its own advance")
    void otherWhitespaceIsSubstituted() {
        for (int whitespace : OTHER_WHITESPACE) {
            float expected = advance(Vanilla.REGULAR, whitespace);
            Set<Integer> picks = new HashSet<>();

            for (long seed = 0; seed < SEEDS; seed++) {
                long drawn = seed;
                int pick = Vanilla.REGULAR.obfuscate(whitespace, new Random(seed));
                assertEquals(expected, advance(Vanilla.REGULAR, pick), () -> describe(Vanilla.REGULAR, whitespace, drawn));
                picks.add(pick);
            }

            assertThat(String.format("U+%04X", whitespace), picks, hasItem(not(whitespace)));
        }
    }

    @Test
    @DisplayName("each substituted codepoint costs one draw, bounded by the size of its bucket")
    void oneDrawPerSubstitutedCodepoint() {
        String text = "Hello World";
        GlyphsByWidth table = Vanilla.REGULAR.glyphsByWidth();
        List<Integer> expected = text.codePoints()
            .filter(codepoint -> codepoint != ' ')
            .mapToObj(codepoint -> table.bucket(table.widthOf(codepoint)).length)
            .toList();

        for (long seed = 0; seed < SEEDS; seed++) {
            CountingRandom random = new CountingRandom(seed);
            Vanilla.REGULAR.obfuscate(text, random);
            assertThat(random.bounds(), is(expected));
            assertThat(random.otherDraws(), is(0));
        }
    }

    @Test
    @DisplayName("one seed gives one result, and the seeds do not all give the same one")
    void deterministicForASeed() {
        Set<String> results = new HashSet<>();

        for (long seed = 0; seed < SEEDS; seed++) {
            String first = Vanilla.REGULAR.obfuscate("Hello World", new Random(seed));
            assertThat(Vanilla.REGULAR.obfuscate("Hello World", new Random(seed)), is(first));
            results.add(first);
        }

        assertThat(results.size() > 1, is(true));
    }

    @Test
    @DisplayName("the codepoint count is kept for a run of every supplementary member")
    void codepointCountIsKept() {
        String text = Vanilla.REGULAR.glyphsByWidth()
            .members()
            .filter(Character::isSupplementaryCodePoint)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
        assertThat(text.isEmpty(), is(false));

        int expected = text.codePointCount(0, text.length());
        for (long seed = 0; seed < SEEDS; seed++) {
            String obfuscated = Vanilla.REGULAR.obfuscate(text, new Random(seed));
            assertThat(obfuscated.codePointCount(0, obfuscated.length()), is(expected));
        }
    }

    @Test
    @DisplayName("a codepoint the font lacks is substituted by a member laid out at the same advance")
    void unmappedCodepointTakesItsLayoutAdvance() {
        Set<Integer> members = Vanilla.REGULAR.glyphsByWidth().members().boxed().collect(Collectors.toSet());
        assertThat(members, not(hasItem(UNMAPPED)));

        float expected = advance(Vanilla.REGULAR, UNMAPPED);
        for (long seed = 0; seed < SEEDS; seed++) {
            int pick = Vanilla.REGULAR.obfuscate(UNMAPPED, new Random(seed));
            assertThat(members, hasItem(pick));
            assertThat(advance(Vanilla.REGULAR, pick), is(expected));
        }
    }

    @Test
    @DisplayName("a control the JDK draws as nothing is no candidate and keeps itself without a draw")
    void invisibleControlsAreNoCandidates() {
        Set<Integer> members = Vanilla.REGULAR.glyphsByWidth().members().boxed().collect(Collectors.toSet());

        for (int control : new int[] {0x09, 0x0A, 0x0D}) {
            assertThat(String.format("U+%04X is a member", control), members.contains(control), is(false));
            for (long seed = 0; seed < SEEDS; seed++) {
                Random random = new Random(seed);
                assertThat(describe(Vanilla.REGULAR, control, seed), Vanilla.REGULAR.obfuscate(control, random), is(control));
                assertThat(describe(Vanilla.REGULAR, control, seed), random.nextInt(), is(new Random(seed).nextInt()));
            }
        }
    }

    @Test
    @DisplayName("the table's advance is the advance layout gives the glyph")
    void tableAgreesWithLayout() {
        for (Vanilla font : List.of(Vanilla.REGULAR, Vanilla.GALACTIC, Vanilla.ILLAGERALT)) {
            GlyphsByWidth table = font.glyphsByWidth();
            for (int codepoint : sample(font))
                assertEquals(advance(font, codepoint), table.widthOf(codepoint), () -> describe(font, codepoint, 0));
        }
    }

    @Test
    @DisplayName("a colour raster row draws among the font's rows and its fallback's glyphs at its advance")
    void colourRowDrawsAmongRowsAndFallback() {
        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        List<Integer> regular = boxed(Vanilla.REGULAR.glyphsByWidth().bucket(16f));

        List<Integer> candidates = new ArrayList<>(regular);
        candidates.addAll(DEMO_ROWS_AT_16_PX);
        candidates.sort(null);
        assertThat(boxed(demo.glyphsByWidth().bucket(16f)), is(candidates));

        for (long seed = 0; seed < SEEDS; seed++) {
            int pick = demo.obfuscate(ColorFontFixtures.CP_FLAT, new Random(seed));
            assertThat(advance(demo, pick), is(16f));
            assertThat(candidates, hasItem(pick));
        }
    }

    @Test
    @DisplayName("a colour space row outside 0..32 mcPixels comes back as itself after one draw")
    void colourFishyRowKeepsItself() {
        // the only -16 px member, so the pick is the row itself; GlyphsByWidthTest refuses a pick of another member
        for (long seed = 0; seed < SEEDS; seed++) {
            CountingRandom random = new CountingRandom(seed);
            assertThat(ColorFontFixtures.demoFont().obfuscate(ColorFontFixtures.CP_NEG_SPACE, random), is(ColorFontFixtures.CP_NEG_SPACE));
            assertThat(random.bounds(), is(List.of(1)));
        }
    }

    @Test
    @DisplayName("a colour space row no other glyph shares an advance with costs one draw over itself")
    void colourSingletonBucketDrawsOnce() {
        MinecraftFont.Color demo = ColorFontFixtures.demoFont();

        for (long seed = 0; seed < SEEDS; seed++) {
            CountingRandom random = new CountingRandom(seed);
            assertThat(demo.obfuscate(ColorFontFixtures.CP_FRAC_SPACE, random), is(ColorFontFixtures.CP_FRAC_SPACE));
            assertThat(random.bounds(), is(List.of(1)));
        }
    }

    @Test
    @DisplayName("each colour font id builds its own table from its own rows")
    void colourFontIdHasItsOwnTable() {
        MinecraftFont.Color alt = ColorFontFixtures.altFont();

        List<Integer> candidates = new ArrayList<>(boxed(Vanilla.REGULAR.glyphsByWidth().bucket(16f)));
        candidates.add(ColorFontFixtures.CP_FLAT);
        candidates.sort(null);
        assertThat(boxed(alt.glyphsByWidth().bucket(16f)), is(candidates));

        for (long seed = 0; seed < SEEDS; seed++) {
            int pick = alt.obfuscate(ColorFontFixtures.CP_FLAT, new Random(seed));
            assertThat(advance(alt, pick), is(16f));
            assertThat(DEMO_ROWS_AT_16_PX.subList(1, DEMO_ROWS_AT_16_PX.size()), not(hasItem(pick)));
        }
    }

    @Test
    @DisplayName("a colour row over a codepoint the fallback maps files it at the row's advance alone")
    void colourRowReplacesTheFallbacksAdvance() {
        FontId fontId = FontId.parse("synth:over");
        ColorGlyphSidecar sidecar = ColorGlyphSidecar.parse(new StringReader("""
            {
              "schema_version": 2, "units_per_em": 1024,
              "glyphs": [
                {"font_id": "synth:over", "codepoint": 65, "gid": null, "advance": 1024, "strike_ppem": null}
              ]
            }
            """));
        byte[] ttf = ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF);
        MinecraftFont.Color font = MinecraftFont.Color.of(fontId, ttf, sidecar, Vanilla.REGULAR);
        GlyphsByWidth table = font.glyphsByWidth();
        float fallbackWidth = Vanilla.REGULAR.glyphsByWidth().widthOf('A');

        assertThat(fallbackWidth == 16f, is(false));
        assertThat(table.widthOf('A'), is(16f));
        assertThat(boxed(table.bucket(16f)), hasItem((int) 'A'));
        assertThat(boxed(table.bucket(fallbackWidth)), not(hasItem((int) 'A')));

        for (long seed = 0; seed < SEEDS; seed++)
            assertThat(advance(font, font.obfuscate('A', new Random(seed))), is(16f));
    }

    @Test
    @DisplayName("a colour font over a colour fallback files the fallback's rows at their advances")
    void colourFallbackContributesItsRows() {
        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        MinecraftFont.Color outer = MinecraftFont.Color.of(
            ColorFontFixtures.ALT, ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF), ColorFontFixtures.sidecar(), demo
        );
        GlyphsByWidth table = outer.glyphsByWidth();

        assertThat(boxed(table.bucket(16f)), is(boxed(demo.glyphsByWidth().bucket(16f))));
        assertThat(table.widthOf(ColorFontFixtures.CP_NEG_SPACE), is(outer.glyph(ColorFontFixtures.CP_NEG_SPACE).signedAdvance()));
    }

    private static float advance(@NotNull MinecraftFont font, int codepoint) {
        return font.glyph(codepoint).signedAdvance();
    }

    /**
     * Returns every 41st member of a font's table, all of U+0021..U+007E and {@link #UNMAPPED} - a
     * spread over the font's codepoints that does not rasterize every glyph it maps.
     */
    private static int @NotNull [] sample(@NotNull Vanilla font) {
        int[] members = font.glyphsByWidth().members().toArray();
        IntStream spread = IntStream.range(0, members.length)
            .filter(index -> index % 41 == 0)
            .map(index -> members[index]);

        return IntStream.concat(IntStream.concat(spread, IntStream.rangeClosed(0x21, 0x7E)), IntStream.of(UNMAPPED))
            .distinct()
            .toArray();
    }

    private static @NotNull List<Integer> boxed(int @NotNull [] codepoints) {
        return IntStream.of(codepoints).boxed().toList();
    }

    private static @NotNull String describe(@NotNull MinecraftFont font, int codepoint, long seed) {
        return String.format("%s U+%04X seed %d", font.fontId(), codepoint, seed);
    }

}
