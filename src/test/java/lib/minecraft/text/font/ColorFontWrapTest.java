package lib.minecraft.text.font;

import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * The lines {@link TextSegment#wrap(int)} answers for text in the demo colour font, whose space glyphs hold
 * the only negative and fractional advances in the fixtures - each the lines the 26.1 client's own line
 * splitter answers at those advances.
 */
@DisplayName("TextSegment#wrap measures a colour font's negative and fractional advances as the client does")
class ColorFontWrapTest {

    /**
     * Wraps text in the demo font and answers each line's run texts, in order.
     *
     * @param text the text
     * @param width the width it wraps at, in mcPixels
     * @return each line's run texts
     */
    private static List<List<String>> wrapped(String text, int width) {
        List<LineSegment> lines = TextSegment.literal(text).mutate().font(ColorFontFixtures.DEMO).build().wrap(width);
        List<List<String>> texts = new ArrayList<>();

        for (LineSegment line : lines)
            texts.add(line.getSegments().stream().map(TextSegment::getText).toList());

        return texts;
    }

    @Test
    @DisplayName("a negative advance counts as a visible codepoint, as the client's line splitter counts any advance "
        + "that is not 0")
    void aNegativeAdvanceIsVisible() {
        MinecraftFont.register(ColorFontFixtures.demoFont());

        try {
            String negative = Character.toString(ColorFontFixtures.CP_NEG_SPACE);

            assertAll(
                () -> assertThat("before a codepoint wider than the line",
                    wrapped(negative + "\u00C6", 1),
                    contains(List.of(negative), List.of("\u00C6"))),
                () -> assertThat("before a narrower one",
                    wrapped(negative + "aa", 1),
                    contains(List.of(negative + "a"), List.of("a")))
            );
        } finally {
            MinecraftFont.unregister(ColorFontFixtures.DEMO);
        }
    }

    @Test
    @DisplayName("a fractional advance is measured as it is, not as a whole mcPixel, as the client's line splitter "
        + "measures a float advance")
    void aFractionalAdvanceIsMeasuredAsItIs() {
        MinecraftFont.register(ColorFontFixtures.demoFont());

        try {
            String fraction = Character.toString(ColorFontFixtures.CP_FRAC_SPACE);

            assertThat("each three quarters of an mcPixel, which makes a line visible and adds up past the width",
                wrapped(fraction + fraction, 1),
                contains(List.of(fraction), List.of(fraction)));
        } finally {
            MinecraftFont.unregister(ColorFontFixtures.DEMO);
        }
    }

    @Test
    @DisplayName("a width below 1 mcPixel wraps at 1, as the client's line splitter does")
    void aWidthBelowOneWrapsAtOne() {
        MinecraftFont.register(ColorFontFixtures.demoFont());

        try {
            String text = Character.toString(ColorFontFixtures.CP_FRAC_SPACE)
                + Character.toString(ColorFontFixtures.CP_NEG_SPACE)
                + Character.toString(ColorFontFixtures.CP_FLAT);

            assertAll(
                () -> assertThat("a width of 0", wrapped(text, 0), contains(List.of(text))),
                () -> assertThat("as a width of 1 does", wrapped(text, 1), contains(List.of(text)))
            );
        } finally {
            MinecraftFont.unregister(ColorFontFixtures.DEMO);
        }
    }

}
