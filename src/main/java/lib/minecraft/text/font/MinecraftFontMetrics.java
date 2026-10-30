package lib.minecraft.text.font;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.FontMetrics;

/**
 * A {@link FontMetrics} implementation shared by every {@link MinecraftFont} kind. Every advance
 * flows through {@link #advanceOf(int)} - {@code font.glyph(cp).signedAdvance()} - so the measure
 * path and the layout/draw path agree exactly: {@link #stringAdvanceX(String)} equals the
 * {@link MinecraftGlyphVector#advanceX() vector advance} for the same text.
 * <p>
 * The AWT rendering context is not held here: it lives on {@link MinecraftFont.Vanilla}, the only
 * kind that rasterizes glyphs. This type carries just the underlying AWT {@link Font} (needed for
 * the {@link FontMetrics} superclass) and the cached line geometry; the overridden measurement
 * methods make the superclass font inert.
 */
@Getter
public final class MinecraftFontMetrics extends FontMetrics {

    @Getter(AccessLevel.NONE)
    private final @NotNull MinecraftFont mcFont;

    private final int ascent;
    private final int descent;
    private final int height;

    /**
     * Builds metrics bound to a font. The {@link FontMetrics} superclass requires the AWT font at
     * construction, so this is the only entry point - there is no separate factory.
     *
     * @param mcFont the font these metrics measure
     * @param awtFont the underlying AWT font (vanilla's own font, a colour font's mono-fallback font,
     * or {@link MinecraftFont.Vanilla#REGULAR}'s for the missing font, whose line geometry it takes)
     * passed to the {@link FontMetrics} superclass
     * @param ascent the ascent in output pixels
     * @param descent the descent in output pixels
     * @param height the line height in output pixels
     */
    public MinecraftFontMetrics(@NotNull MinecraftFont mcFont, @NotNull Font awtFont, int ascent, int descent, int height) {
        super(awtFont);
        this.mcFont = mcFont;
        this.ascent = ascent;
        this.descent = descent;
        this.height = height;
    }

    /**
     * The signed advance of a single codepoint in output pixels, taken from the glyph itself. May be
     * negative or fractional for colour space/raster glyphs; equals the integer advance for mono
     * glyphs.
     *
     * @param codepoint the Unicode codepoint
     * @return the signed advance in output pixels
     */
    public double advanceOf(int codepoint) {
        return this.mcFont.glyph(codepoint).signedAdvance();
    }

    /**
     * Alias for {@link #advanceOf(int)} matching the design's metric vocabulary.
     *
     * @param codepoint the Unicode codepoint
     * @return the advance in output pixels
     */
    public double advanceX(int codepoint) {
        return advanceOf(codepoint);
    }

    /**
     * The total signed advance of a string in output pixels. Drives {@link MinecraftFont#walk the same
     * accumulation walk} the layout and draw paths use - with a {@link MinecraftFont.GlyphSink#NOOP
     * no-op} sink, taking only its returned total - so measure equals draw by construction. The walk
     * iterates by codepoint (not char) so supplementary-plane PUA glyphs measure correctly.
     *
     * @param text the text to measure
     * @return the total advance in output pixels
     */
    public double stringAdvanceX(@NotNull String text) {
        return this.mcFont.walk(text, MinecraftFont.GlyphSink.NOOP);
    }

    /**
     * Measures the total signed advance of a string in mcPixels with its fraction kept -
     * {@link #stringAdvanceX(String)} divided by {@link MinecraftFont#MC_PIXEL_SCALE}. A run drawn
     * through {@link MinecraftGraphics#drawString(String, float, float)} at this run's origin plus this
     * width continues this run's pen. Walks codepoints, as {@link #stringAdvanceX(String)} does.
     *
     * @param text the text to measure
     * @return the total advance in mcPixels
     */
    public float stringAdvanceMcPixels(@NotNull String text) {
        return (float) (stringAdvanceX(text) / MinecraftFont.MC_PIXEL_SCALE);
    }

    /**
     * Measures a string's width in whole mcPixels, as the client's {@code Font#width} does - the
     * {@linkplain #stringAdvanceMcPixels(String) advance} rounded up, so a run ending on half an
     * mcPixel is answered the whole mcPixel it reaches into.
     *
     * @param text the text to measure
     * @return the width in whole mcPixels
     */
    public int stringWidthMcPixels(@NotNull String text) {
        return (int) Math.ceil(stringAdvanceMcPixels(text));
    }

    /**
     * Measures out the head of a string that fits a width in whole mcPixels, as the client's
     * {@code Font#plainSubstrByWidth} does. Walks codepoints, as {@link #stringAdvanceX(String)} does,
     * taking each one's advance in mcPixels as a {@code float} from what is left of the width, and keeps
     * each codepoint while what is left is not negative - so a codepoint ending exactly on the width is
     * kept, and one ending half an mcPixel past it is not. The walk stops at the first codepoint it does
     * not keep, even where a negative advance after it would bring the run back within the width, and a
     * surrogate pair is kept or dropped whole. The text is read plain, a format code measured as the
     * characters it is written with.
     *
     * @param text the text to cut
     * @param maxWidthMcPixels the width the head may run to, in whole mcPixels
     * @return the text up to the first codepoint that does not fit, empty when that is its first
     */
    public @NotNull String stringHeadByWidthMcPixels(@NotNull String text, int maxWidthMcPixels) {
        float remaining = maxWidthMcPixels;
        int end = 0;
        while (end < text.length()) {
            int codepoint = text.codePointAt(end);
            remaining -= (float) (advanceOf(codepoint) / MinecraftFont.MC_PIXEL_SCALE);
            if (remaining < 0)
                break;

            end += Character.charCount(codepoint);
        }
        return text.substring(0, end);
    }

    @Override
    public int stringWidth(@NotNull String str) {
        return Math.round((float) stringAdvanceX(str));
    }

    @Override
    public int charWidth(int codepoint) {
        return Math.round((float) advanceOf(codepoint));
    }

    @Override
    public int charWidth(char ch) {
        return Math.round((float) advanceOf(ch));
    }

    /**
     * Returns the ascent in mcPixels - the logical font unit where one mcPixel equals
     * {@link MinecraftFont#MC_PIXEL_SCALE} native output pixels.
     *
     * @return the ascent in mcPixels
     */
    public int getAscentMcPixels() {
        return this.ascent / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Returns the descent in mcPixels.
     *
     * @return the descent in mcPixels
     */
    public int getDescentMcPixels() {
        return this.descent / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Returns the total line height (ascent + descent + leading) in mcPixels.
     *
     * @return the height in mcPixels
     */
    public int getHeightMcPixels() {
        return this.height / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * The line height in mcPixels. Alias for {@link #getHeightMcPixels()} matching the colour-font
     * metric vocabulary, so colour and vanilla text share a baseline grid.
     *
     * @return the line height in mcPixels
     */
    public int lineHeightMcPixels() {
        return getHeightMcPixels();
    }

    /**
     * @return the font id these metrics are bound to
     */
    public @NotNull FontId fontId() {
        return this.mcFont.fontId();
    }

}
