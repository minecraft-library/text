package lib.minecraft.text.font;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * The glyphs of one font grouped by their non-bold layout advance - the candidates obfuscated text
 * picks from.
 * <p>
 * A bucket holds the members laid out at exactly one advance, in output pixels, so every candidate for
 * a codepoint moves the pen by exactly what the codepoint itself does. Each bucket lists its members
 * by ascending codepoint, which makes a pick a function of the members and the draw alone. A member
 * whose advance the client refuses to draw stays in its bucket; {@link #obfuscate} refuses it when it
 * is picked.
 * <p>
 * A table is immutable once built, so one instance serves every thread.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class GlyphsByWidth {

    /** The codepoint obfuscated text keeps, with no draw. */
    private static final int SPACE = 0x20;

    /** The widest advance the client draws, in mcPixels, at either weight. */
    private static final float MAX_ADVANCE = 32.0f;

    /**
     * What bold adds to a glyph's advance, in mcPixels - the client's default, which its bitmap and
     * space glyphs keep.
     */
    private static final float BOLD_OFFSET = 1.0f;

    /** Candidates per advance in output pixels, each non-empty and ascending by codepoint. */
    private final @NotNull Map<Float, int[]> buckets;

    /** Every member codepoint, ascending. */
    private final int @NotNull [] members;

    /** The non-bold layout advance of any codepoint, mapped or not, in output pixels. */
    private final @NotNull IntToDoubleFunction advance;

    /**
     * Builds a table over {@code members}, reading each one's advance through {@code advance}.
     *
     * @param members the codepoints the font draws a glyph for, in any order and possibly repeated
     * @param advance the non-bold layout advance of any codepoint, in output pixels
     * @return the table
     */
    static @NotNull GlyphsByWidth of(int @NotNull [] members, @NotNull IntToDoubleFunction advance) {
        int[] sorted = IntStream.of(members).sorted().distinct().toArray();

        Map<Float, IntStream.Builder> grouped = new HashMap<>();
        for (int member : sorted)
            grouped.computeIfAbsent(toWidth(advance.applyAsDouble(member)), width -> IntStream.builder()).add(member);

        Map<Float, int[]> buckets = new HashMap<>();
        grouped.forEach((width, bucket) -> buckets.put(width, bucket.build().toArray()));
        return new GlyphsByWidth(buckets, sorted, advance);
    }

    /**
     * Returns the codepoint obfuscated text draws in place of {@code codepoint}.
     * <p>
     * U+0020 comes back with no draw. Any other codepoint takes the bucket of its own advance, and
     * where no member shares that advance it comes back with no draw. Otherwise exactly one
     * {@code random.nextInt(size)} picks a member - for a one-member bucket too - and the pick comes
     * back - unless its advance, plain or bold, lies outside {@code 0..32} mcPixels, when the
     * original does.
     *
     * @param codepoint the codepoint being obfuscated
     * @param random the source of the draw
     * @return the codepoint to draw in its place
     */
    int obfuscate(int codepoint, @NotNull RandomGenerator random) {
        if (codepoint == SPACE) return codepoint;

        int[] candidates = this.buckets.get(widthOf(codepoint));
        if (candidates == null) return codepoint;

        int pick = candidates[random.nextInt(candidates.length)];
        return hasFishyAdvance(widthOf(pick)) ? codepoint : pick;
    }

    /**
     * Returns the advance {@code codepoint} lays out at in the non-bold font, in output pixels - the
     * key of the bucket its candidates come from.
     *
     * @param codepoint the codepoint, mapped or not
     * @return the advance in output pixels
     */
    float widthOf(int codepoint) {
        return toWidth(this.advance.applyAsDouble(codepoint));
    }

    /**
     * Returns the members laid out at {@code width}, ascending by codepoint.
     *
     * @param width the advance in output pixels
     * @return a copy of the bucket, empty where no member lays out at the advance
     */
    int @NotNull [] bucket(float width) {
        int[] bucket = this.buckets.get(width + 0.0f);
        return bucket == null ? new int[0] : bucket.clone();
    }

    /**
     * Streams the member codepoints in ascending order.
     *
     * @return the members
     */
    @NotNull IntStream members() {
        return IntStream.of(this.members);
    }

    /**
     * Returns whether the client refuses to draw a glyph of this advance - one outside
     * {@code 0..32} mcPixels at the plain weight, or at the bold weight.
     */
    private static boolean hasFishyAdvance(float width) {
        float plain = width / MinecraftFont.MC_PIXEL_SCALE;
        if (plain < 0 || plain > MAX_ADVANCE) return true;

        float bold = plain + BOLD_OFFSET;
        return bold < 0 || bold > MAX_ADVANCE;
    }

    /**
     * Narrows an advance to the bucket key it files under, folding {@code -0.0} into {@code 0.0} so a
     * signed zero is one advance.
     */
    private static float toWidth(double advance) {
        return (float) advance + 0.0f;
    }

}
