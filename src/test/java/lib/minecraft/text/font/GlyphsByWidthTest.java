package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

@DisplayName("GlyphsByWidth groups glyphs by advance and picks among them as the client does")
class GlyphsByWidthTest {

    private static final int SEEDS = 64;

    @Test
    @DisplayName("an advance no member shares keeps the original and draws nothing")
    void emptyBucketKeepsOriginalWithoutDraw() {
        GlyphsByWidth table = GlyphsByWidth.of(new int[] {0x41, 0x42}, codepoint -> codepoint == 0x43 ? 7 : 12);

        for (long seed = 0; seed < SEEDS; seed++) {
            Random random = new Random(seed);
            assertThat(table.obfuscate(0x43, random), is(0x43));
            assertThat(random.nextInt(), is(new Random(seed).nextInt()));
        }
    }

    @Test
    @DisplayName("a bucket lists its members by codepoint whatever order they arrive in")
    void bucketsAscendByCodepoint() {
        GlyphsByWidth table = GlyphsByWidth.of(new int[] {0x42, 0x41}, codepoint -> 12);

        assertThat(table.bucket(12f), is(new int[] {0x41, 0x42}));
        assertThat(table.members().toArray(), is(new int[] {0x41, 0x42}));
    }

    @Test
    @DisplayName("-0.0 and 0.0 are one advance")
    void signedZeroIsOneAdvance() {
        GlyphsByWidth table = GlyphsByWidth.of(new int[] {0x41, 0x42}, codepoint -> codepoint == 0x41 ? -0.0 : 0.0);

        assertThat(table.bucket(0f), is(new int[] {0x41, 0x42}));
        assertThat(table.bucket(-0f), is(new int[] {0x41, 0x42}));
    }

    @Test
    @DisplayName("java.util.Random draws the sequence of the client's LegacyRandomSource")
    void randomDrawsTheClientSequence() {
        Random random = new Random(42);
        LegacyRandomSource legacy = new LegacyRandomSource(42);

        for (int expected : new int[] {2867, 4599, 3627, 3620, 3588, 637}) {
            assertThat(random.nextInt(4659), is(expected));
            assertThat(legacy.nextInt(4659), is(expected));
        }
    }

    @Test
    @DisplayName("a pick outside 0..32 mcPixels keeps the original after its one draw")
    void fishyPickKeepsOriginal() {
        assertPicksAreFishy(70);   // 35 mcPixels
    }

    @Test
    @DisplayName("a pick below 0 mcPixels keeps the original after its one draw, though its bold advance is 0")
    void negativeAdvanceMakesPickFishy() {
        assertPicksAreFishy(-2);   // -1 mcPixel, 0 in bold
    }

    @Test
    @DisplayName("a pick whose bold advance passes 32 mcPixels keeps the original, and one at 32 does not")
    void boldAdvanceMakesPickFishy() {
        assertPicksAreFishy(63);   // 31.5 mcPixels, 32.5 in bold

        GlyphsByWidth table = GlyphsByWidth.of(new int[] {0x41, 0x42}, codepoint -> 62);   // 31 mcPixels, 32 in bold
        Random random = new Random(0);
        Set<Integer> picks = new HashSet<>();
        for (int draw = 0; draw < SEEDS; draw++)
            picks.add(table.obfuscate(0x41, random));

        assertThat(picks, containsInAnyOrder(0x41, 0x42));
    }

    /**
     * Asserts that two members at one advance each come back as themselves on every draw of one
     * stream, each after exactly one {@code nextInt(2)} - which holds only if the pick is refused,
     * since otherwise about half the draws answer the other member.
     * <p>
     * The draws come from one stream rather than a fresh {@code new Random(seed)} each, because the
     * first {@code nextInt(2)} of {@code java.util.Random} answers {@code 1} for every seed from
     * {@code 0} to {@code 63}, so fresh seeds would never pick index {@code 0}.
     */
    private static void assertPicksAreFishy(double advance) {
        GlyphsByWidth table = GlyphsByWidth.of(new int[] {0x41, 0x42}, codepoint -> advance);
        CountingRandom random = new CountingRandom(0);

        for (int draw = 0; draw < SEEDS; draw++) {
            assertThat(table.obfuscate(0x41, random), is(0x41));
            assertThat(table.obfuscate(0x42, random), is(0x42));
        }

        assertThat(random.bounds(), is(Collections.nCopies(2 * SEEDS, 2)));
        assertThat(random.otherDraws(), is(0));
    }

    /**
     * The client's {@code LegacyRandomSource} and its {@code BitRandomSource#nextInt(int)}, written
     * out op for op: the seed is xored with the multiplier and masked to 48 bits, each step multiplies
     * by {@code 0x5DEECE66D} and adds {@code 11}, and a bounded draw is the power-of-two shortcut or
     * the reject loop.
     */
    private static final class LegacyRandomSource {

        private static final long MULTIPLIER = 0x5DEECE66DL;
        private static final long MASK = (1L << 48) - 1;

        private long seed;

        LegacyRandomSource(long seed) {
            this.seed = (seed ^ MULTIPLIER) & MASK;
        }

        int next(int bits) {
            this.seed = this.seed * MULTIPLIER + 11L & MASK;
            return (int) (this.seed >> 48 - bits);
        }

        int nextInt(int bound) {
            if ((bound & bound - 1) == 0)
                return (int) ((long) bound * next(31) >> 31);

            int bits;
            int value;
            do {
                bits = next(31);
                value = bits % bound;
            } while (bits - value + (bound - 1) < 0);
            return value;
        }

    }

}
