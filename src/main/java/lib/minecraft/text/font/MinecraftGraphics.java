package lib.minecraft.text.font;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelGraphics;
import lib.minecraft.text.TextSegment;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.font.GlyphVector;

/**
 * A {@link PixelGraphics} subclass that renders {@link MinecraftFont} glyphs directly into a
 * {@link PixelBuffer}. Glyph origins are mcPixels, fractional where a run starts after another, as
 * the client keeps its pen as a float in GUI pixels. The {@code mcPixel -> output-pixel} conversion
 * via {@link MinecraftFont#MC_PIXEL_SCALE} happens only at the blit, with one rounding per glyph of
 * origin plus pen - see {@link #drawString(String, float, float)}. The inherited rectangle, image,
 * clip and translate forms stay in output pixels.
 * <p>
 * A glyph is laid as the client lays text. A mono glyph's pixel takes its texel's alpha times the
 * colour's, rounded half up once; a pixel whose alpha comes to {@link #DISCARD_ALPHA} or less is not
 * laid, as the client's text shader discards a fragment below 0.1, and any other is blended over the
 * buffer with {@link BlendMode#NORMAL}, the client's translucent blend. A colour glyph keeps its own
 * pixels, the colour reaching none of them. A strikethrough or underline bar, given in mcPixels to
 * {@link #fillEffect(float, float, float, float)}, is laid by the same step at full coverage.
 * <p>
 * Text is laid in the convention the buffer holds, each pixel as
 * {@link PixelBuffer#fillRect(int, int, int, int, int, BlendMode)} lays a straight colour under
 * {@link BlendMode#NORMAL}: onto premultiplied colour, or a buffer whose alpha carries no data, by
 * {@code NORMAL}'s formula, which over transparency leaves premultiplied colour as the client's
 * translucent blend leaves it in the framebuffer, and onto straight colour by
 * {@link BlendMode#STRAIGHT}'s, which leaves straight colour. A canvas, made by
 * {@link PixelBuffer#canvas(int, int)}, holds what the client's framebuffer holds.
 * <p>
 * Rendering is always at the font's native rasterization (no glyph pixel replication). Callers
 * that need a larger glyph for GUI-density use cases (e.g. stack counts on a hi-res icon)
 * should rasterize into a native-size transparent scratch canvas and lay it down with
 * {@link PixelBuffer#blitScaled(PixelBuffer, int, int, int, int)} - the glyph is blended once onto the
 * scratch and the scratch composited once, as premultiplied colour, so its alpha is applied once while
 * the caller chooses the upscale ratio independently. Each of the two blends rounds, so a pixel laid
 * this way can differ from a direct draw's by one in a channel.
 *
 * @see MinecraftFont
 * @see PixelGraphics
 */
public class MinecraftGraphics extends PixelGraphics {

    /**
     * The highest alpha a text pixel can come to and still be discarded, as the client's text shader
     * discards a fragment whose alpha is below 0.1 - 25 of 255 is 0.098 and 26 is 0.102.
     */
    public static final int DISCARD_ALPHA = 25;

    private @NotNull MinecraftFont currentMcFont;

    /**
     * Creates a Minecraft graphics context for the given buffer.
     *
     * @param target the pixel buffer to draw onto
     */
    public MinecraftGraphics(@NotNull PixelBuffer target) {
        super(target);
        this.currentMcFont = MinecraftFont.Vanilla.REGULAR;
    }

    private MinecraftGraphics(@NotNull MinecraftGraphics source) {
        super(source);
        this.currentMcFont = source.currentMcFont;
    }

    // --- text rendering ---

    /**
     * Draws a string at the given whole mcPixel origin, exactly where
     * {@link #drawString(String, float, float)} draws the same values.
     *
     * @param str the text to draw
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     */
    @Override
    public void drawString(@NotNull String str, int xMcPx, int yMcPx) {
        drawString(str, (float) xMcPx, (float) yMcPx);
    }

    /**
     * Draws a string at the given mcPixel origin with its fraction kept. The baseline sits at
     * {@code yMcPx}; the cursor starts at {@code xMcPx}. The origin is converted to buffer coordinates
     * via {@code mcPx * MinecraftFont.MC_PIXEL_SCALE} under the translation and left unrounded; each
     * glyph's buffer X is that origin plus the glyph's pen, rounded once, half up, at the blit. A run
     * drawn at another's origin plus {@link MinecraftFontMetrics#stringAdvanceMcPixels(String) its width}
     * therefore lands every glyph where the joined run does.
     * <p>
     * The current colour's alpha is laid with it: a mono glyph's pixel takes its texel's alpha times the
     * colour's, and a pixel whose alpha comes to {@link #DISCARD_ALPHA} or less is not laid, as the
     * client's text shader discards below 0.1.
     *
     * @param str the text to draw
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     */
    @Override
    public void drawString(@NotNull String str, float xMcPx, float yMcPx) {
        if (str.isEmpty()) return;
        double originX = toBuffer(translateX(), xMcPx);
        int cy = (int) Math.round(toBuffer(translateY(), yMcPx));
        int fillArgb = getColor().getRGB();
        // Drive the shared advance walk directly - no MinecraftGlyphVector/list allocation on the hot path.
        this.currentMcFont.walk(str, (glyph, penX) -> blitGlyph(glyph, (int) Math.round(originX + penX), cy, fillArgb));
    }

    /**
     * Lays a strikethrough or underline bar given in mcPixels, in the current colour. The client draws
     * such a bar with its font's white effect glyph through the text shader, so the bar is laid as a
     * glyph pixel covered whole: each pixel takes the colour's alpha, a colour at {@link #DISCARD_ALPHA}
     * or less lays nothing, and any other blends over the buffer with {@link BlendMode#NORMAL}.
     * <p>
     * Each edge is converted to buffer coordinates via {@code mcPx * MinecraftFont.MC_PIXEL_SCALE} under
     * the translation and rounded half up once, as a glyph's origin plus pen is in
     * {@link #drawString(String, float, float)}, so the bar's width and height are the differences of its
     * rounded edges. The clip is honoured as it is for a glyph. The inherited
     * {@link #fillRect(int, int, int, int)} stays in output pixels and composites the current colour
     * source-over in the convention the buffer holds, and lays a colour whose alpha is
     * {@link #DISCARD_ALPHA} or less too, where the text shader discards one.
     *
     * @param xMcPx the left edge in mcPixels
     * @param yMcPx the top edge in mcPixels
     * @param widthMcPx the width in mcPixels
     * @param heightMcPx the height in mcPixels
     */
    public void fillEffect(float xMcPx, float yMcPx, float widthMcPx, float heightMcPx) {
        double left = toBuffer(translateX(), xMcPx);
        double top = toBuffer(translateY(), yMcPx);
        Rectangle area = drawableArea();
        int x0 = Math.max((int) Math.round(left), area.x);
        int y0 = Math.max((int) Math.round(top), area.y);
        int x1 = Math.min((int) Math.round(left + (double) widthMcPx * MinecraftFont.MC_PIXEL_SCALE), area.x + area.width);
        int y1 = Math.min((int) Math.round(top + (double) heightMcPx * MinecraftFont.MC_PIXEL_SCALE), area.y + area.height);
        int argb = getColor().getRGB();
        PixelBuffer target = target();

        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                layTextPixel(target, x, y, 255, argb);
    }

    /**
     * Converts an mcPixel coordinate to an unrounded buffer coordinate under a translation, so a
     * glyph's pen joins it before the one rounding at the blit.
     *
     * @param translate the translation in output pixels
     * @param mcPx the coordinate in mcPixels
     * @return the buffer coordinate in output pixels, unrounded
     */
    private static double toBuffer(int translate, float mcPx) {
        return translate + (double) mcPx * MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Blits one glyph at buffer coordinates {@code (x, y)}, offset by the glyph's bearing.
     * <p>
     * Monochrome glyphs are tinted as the client's text shader tints them: each pixel takes
     * {@code tintArgb}'s red, green and blue, and its texel's alpha times {@code tintArgb}'s; a pixel
     * whose alpha comes to {@link #DISCARD_ALPHA} or less is not laid, and any other blends with
     * {@link BlendMode#NORMAL} in the convention the target holds. Colour glyphs
     * ({@link MinecraftGlyph#color()}) carry their own RGBA artwork, read as straight colour, and are
     * blitted natively the same way - {@code tintArgb} is ignored, its alpha included - so pack
     * {@code sbix} strikes keep their authored colours.
     *
     * @param glyph the glyph to blit
     * @param x the buffer X of the cursor
     * @param y the buffer Y of the cursor
     * @param tintArgb the colour monochrome glyphs are laid in, its alpha included (ignored for colour glyphs)
     */
    void blitGlyph(@NotNull MinecraftGlyph glyph, int x, int y, int tintArgb) {
        if (glyph.kind() == MinecraftGlyphVector.Kind.SPACE) return;   // advance-only sentinel, paints nothing
        boolean color = glyph.color();
        if (!color && ColorMath.alpha(tintArgb) <= DISCARD_ALPHA) return;   // the shader discards every pixel
        PixelBuffer bitmap = glyph.bitmap();
        int bw = bitmap.width();
        int bh = bitmap.height();
        int gx = x + glyph.bearingX();
        int gy = y + glyph.bearingY();
        PixelBuffer target = target();

        Rectangle area = drawableArea();
        int clipX0 = area.x;
        int clipY0 = area.y;
        int clipX1 = area.x + area.width;
        int clipY1 = area.y + area.height;

        for (int by = 0; by < bh; by++) {
            int py = gy + by;
            if (py < clipY0 || py >= clipY1) continue;
            for (int bx = 0; bx < bw; bx++) {
                int pixel = bitmap.getPixel(bx, by);
                int alpha = ColorMath.alpha(pixel);
                if (alpha == 0) continue;
                int px = gx + bx;
                if (px < clipX0 || px >= clipX1) continue;

                if (color)
                    target.fillRect(px, py, 1, 1, pixel, BlendMode.NORMAL);
                else
                    layTextPixel(target, px, py, alpha, tintArgb);
            }
        }
    }

    /**
     * The buffer pixels a glyph or a bar may be laid on: the clip, read in buffer pixels, cut to the
     * target, or the whole target when no clip is set.
     *
     * @return the writable area in buffer pixels, empty when the clip misses the target
     */
    private @NotNull Rectangle drawableArea() {
        PixelBuffer target = target();
        Rectangle whole = new Rectangle(target.width(), target.height());
        return getClip() instanceof Rectangle clip ? whole.intersection(clip) : whole;
    }

    /**
     * Lays one text pixel as the client's text shader and translucent blend lay it: the coverage times
     * the colour's alpha, rounded half up once, is the pixel's alpha; a pixel at {@link #DISCARD_ALPHA}
     * or less is not laid, and any other is laid over the target by
     * {@link PixelBuffer#fillRect(int, int, int, int, int, BlendMode)} under {@link BlendMode#NORMAL}, in
     * the convention the target holds. An opaque colour over a whole texel lays its colour as given.
     *
     * @param target the buffer to lay the pixel on
     * @param x the buffer X
     * @param y the buffer Y
     * @param coverage the texel's alpha, {@code 255} where the pixel is covered whole
     * @param argb the colour the pixel is laid in, its alpha included
     */
    private static void layTextPixel(@NotNull PixelBuffer target, int x, int y, int coverage, int argb) {
        int alpha = (coverage * ColorMath.alpha(argb) + 127) / 255;
        if (alpha <= DISCARD_ALPHA) return;
        int source = ColorMath.pack(alpha, ColorMath.red(argb), ColorMath.green(argb), ColorMath.blue(argb));
        target.fillRect(x, y, 1, 1, source, BlendMode.NORMAL);
    }

    // --- colour glyph vector rendering ---

    /**
     * Paints a {@link MinecraftGlyphVector} at an mcPixel origin using the current colour as the
     * mono-glyph tint.
     *
     * @param vector the laid-out colour run
     * @param xMcPx the run origin X in mcPixels
     * @param yMcPx the run origin Y in mcPixels (baseline for mono glyphs)
     */
    public void drawGlyphVector(@NotNull MinecraftGlyphVector vector, int xMcPx, int yMcPx) {
        drawGlyphVector(vector, xMcPx, yMcPx, getColor());
    }

    /**
     * Draws a {@link GlyphVector} at the mcPixel run origin {@code (x, y)}, per the AWT
     * {@link Graphics2D#drawGlyphVector} contract. Only a {@link MinecraftGlyphVector} carries
     * the pack strike bitmaps and sidecar layout this renderer blits, so a foreign {@code GlyphVector}
     * implementation is rejected with {@link IllegalArgumentException} rather than silently
     * mis-rendered: real AWT would reduce it to bare glyph codes and drop both the pack positions and
     * the pack pixels (measured - see {@link MinecraftGlyphVector}). {@code x} and {@code y} are read
     * as the mcPixel origin, matching {@link #drawString}, and keep their fraction to the blit, as
     * {@link #drawString(String, float, float)} does; the current colour tints mono glyphs.
     *
     * @param g the glyph vector, which must be a {@link MinecraftGlyphVector}
     * @param x the run origin X in mcPixels
     * @param y the run origin Y in mcPixels (baseline for mono glyphs)
     * @throws IllegalArgumentException when {@code g} is not a {@link MinecraftGlyphVector}
     */
    @Override
    public void drawGlyphVector(@NotNull GlyphVector g, float x, float y) {
        if (!(g instanceof MinecraftGlyphVector vector))
            throw new IllegalArgumentException(
                "MinecraftGraphics can only draw a MinecraftGlyphVector; a foreign GlyphVector ("
                    + g.getClass().getName() + ") carries no pack strike bitmaps, and AWT would reduce it to glyph "
                    + "codes, dropping the pack layout. Lay text out via MinecraftFont.layout(String).");
        drawGlyphVector(vector, x, y, getColor());
    }

    /**
     * Paints a {@link MinecraftGlyphVector} at an mcPixel origin with its fraction kept, rounding
     * each glyph's origin plus pen once at the blit, as {@link #drawString(String, float, float)} does.
     * <p>
     * Raster glyphs blit their native {@code sbix} strike, which {@code fill} reaches no pixel of; mono
     * fallback glyphs are laid in {@code fill}, its alpha included, and a pixel whose alpha comes to
     * {@link #DISCARD_ALPHA} or less is not laid, as the client's text shader discards below 0.1; space
     * glyphs paint nothing. Pen positions come from the vector's sidecar-driven layout - Java2D's zeroed
     * {@code GlyphVector} advances are never consulted.
     *
     * @param vector the laid-out colour run
     * @param xMcPx the run origin X in mcPixels
     * @param yMcPx the run origin Y in mcPixels (baseline for mono glyphs)
     * @param fill the colour mono glyphs are laid in, its alpha included
     */
    public void drawGlyphVector(@NotNull MinecraftGlyphVector vector, float xMcPx, float yMcPx, @NotNull Color fill) {
        double originX = toBuffer(translateX(), xMcPx);
        int cy = (int) Math.round(toBuffer(translateY(), yMcPx));
        int fillArgb = fill.getRGB();

        for (int i = 0; i < vector.glyphCount(); i++) {
            MinecraftGlyph glyph = vector.positionedGlyph(i);
            blitGlyph(glyph, (int) Math.round(originX + glyph.penX()), cy, fillArgb);   // blitGlyph no-ops on SPACE
        }
    }

    // --- font and metrics ---

    /**
     * Selects a {@link MinecraftFont} variant directly, bypassing AWT's style-bit round trip.
     * <p>
     * Custom-loaded OTF fonts always report {@link Font#PLAIN} from {@link Font#getStyle()}
     * because AWT does not introspect the typeface file - style is whatever was set with
     * {@code deriveFont(style)} (never, for us). Going through {@link #setFont(Font)} would
     * therefore always resolve to {@link MinecraftFont.Vanilla#REGULAR}. Callers that already know
     * which variant they want (e.g. the text pipeline picking BOLD from a
     * {@link TextSegment}'s {@code &l} flag) should use this method
     * instead.
     *
     * @param font the Minecraft font variant to use for subsequent {@link #drawString} calls
     */
    public void setFont(@NotNull MinecraftFont font) {
        this.currentMcFont = font;
    }

    @Override
    public void setFont(@NotNull Font font) {
        this.currentMcFont = MinecraftFont.Vanilla.of(MinecraftFont.Style.of(font.getStyle()));
    }

    @Override
    public @NotNull Font getFont() {
        return this.currentMcFont.metrics().getFont();
    }

    @Override
    public @NotNull FontMetrics getFontMetrics(@NotNull Font f) {
        return MinecraftFont.Vanilla.of(MinecraftFont.Style.of(f.getStyle())).metrics();
    }

    @Override
    public @NotNull FontMetrics getFontMetrics() {
        return this.currentMcFont.metrics();
    }

    // --- copy ---

    @Override
    public @NotNull Graphics create() {
        return new MinecraftGraphics(this);
    }

}
