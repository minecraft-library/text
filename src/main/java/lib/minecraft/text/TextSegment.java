package lib.minecraft.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.EqualsExclude;
import dev.simplified.annotations.EqualsInclude;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.ToString;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.StringUtil;
import lib.minecraft.text.event.ClickEvent;
import lib.minecraft.text.event.HoverEvent;
import lib.minecraft.text.exception.TextJsonException;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A 26.1 text component - what it shows, the style it shows it in, and the components appended after it,
 * each of which inherits that style wherever it sets none of its own.
 *
 * <p>Every style field is three-state, as vanilla's are: absent inherits from the parent, and present
 * {@code false} differs from absent - {@code "italic": false} turns off the italic a lore line otherwise
 * inherits. Each {@code isX()} accessor answers whether the segment is drawn that way, which it is only
 * where the flag is present and {@code true}; each {@code getX()} accessor answers the field itself.</p>
 *
 * <p>{@link #fromJson(JsonElement)} reads every form vanilla reads - a plain string, a non-empty list whose
 * first element the rest are appended to, and an object - and refuses what vanilla refuses, which includes a
 * flag that is not a JSON boolean and a colour name not spelled as vanilla spells it. A {@code gradient}
 * member, which vanilla ignores, carries a {@link GradientSpec}. {@link #toJson()} writes what vanilla
 * writes, a plain string wherever the segment is plain text in no style.</p>
 *
 * <p>Two segments are equal when they hold the same contents, style and siblings, a colour comparing by its
 * value as vanilla compares it - so {@code red} equals {@code #FF5555}.</p>
 *
 * <p>A segment is also the run the font renderer draws. {@link #toLines()} flattens a component into
 * {@link LineSegment lines} of segments, each plain text in the style it is shown in, and
 * {@link #fromLegacy(String, char)} reads section-coded text straight into such runs.</p>
 */
@Getter
@ToString
@ClassBuilder
@EqualsAndHashCode
public final class TextSegment {

    /**
     * What the segment shows before its siblings - plain text unless set otherwise.
     */
    private final @NotNull TextContents contents = TextContents.Text.EMPTY;

    /**
     * The colour, compared by its value.
     */
    @EqualsExclude
    private final @NotNull Optional<ChatColor> color = Optional.empty();

    /**
     * The gradient drawn in place of {@link #color}, which a renderer that cannot draw one falls back to.
     */
    private final @NotNull Optional<GradientSpec> gradient = Optional.empty();

    /**
     * The colour the shadow is drawn in, its alpha included, compared by its ARGB value; a zero alpha
     * draws no shadow.
     */
    @EqualsExclude
    private final @NotNull Optional<ChatColor> shadowColor = Optional.empty();

    /**
     * Whether the text is bold.
     */
    private final @NotNull Optional<Boolean> bold = Optional.empty();

    /**
     * Whether the text is italic.
     */
    private final @NotNull Optional<Boolean> italic = Optional.empty();

    /**
     * Whether the text is underlined.
     */
    private final @NotNull Optional<Boolean> underlined = Optional.empty();

    /**
     * Whether the text is struck through.
     */
    private final @NotNull Optional<Boolean> strikethrough = Optional.empty();

    /**
     * Whether the text is obfuscated.
     */
    private final @NotNull Optional<Boolean> obfuscated = Optional.empty();

    /**
     * What clicking the text does.
     */
    private final @NotNull Optional<ClickEvent> clickEvent = Optional.empty();

    /**
     * What hovering over the text shows.
     */
    private final @NotNull Optional<HoverEvent> hoverEvent = Optional.empty();

    /**
     * The text shift-clicking the segment puts in the chat box.
     */
    private final @NotNull Optional<String> insertion = Optional.empty();

    /**
     * The font the text is drawn in.
     */
    private final @NotNull Optional<FontId> font = Optional.empty();

    /**
     * The siblings appended after the contents, in order.
     */
    private final @NotNull List<TextSegment> extra = List.of();

    /**
     * Builds a segment showing literal text, kept exactly as given, in no style.
     *
     * @param text the text
     * @return the segment
     */
    public static @NotNull TextSegment literal(@NotNull String text) {
        return builder().contents(new TextContents.Text(text)).build();
    }

    /**
     * Returns the text this segment shows on its own, before its siblings and unresolved.
     *
     * @return the text
     */
    public @NotNull String getText() {
        return this.contents.plainText();
    }

    /**
     * Returns whether the text is drawn bold.
     *
     * @return whether bold is present and set
     */
    public boolean isBold() {
        return this.bold.orElse(false);
    }

    /**
     * Returns whether the text is drawn italic.
     *
     * @return whether italic is present and set
     */
    public boolean isItalic() {
        return this.italic.orElse(false);
    }

    /**
     * Returns whether the text is drawn underlined.
     *
     * @return whether underlined is present and set
     */
    public boolean isUnderlined() {
        return this.underlined.orElse(false);
    }

    /**
     * Returns whether the text is drawn struck through.
     *
     * @return whether strikethrough is present and set
     */
    public boolean isStrikethrough() {
        return this.strikethrough.orElse(false);
    }

    /**
     * Returns whether the text is drawn obfuscated.
     *
     * @return whether obfuscated is present and set
     */
    public boolean isObfuscated() {
        return this.obfuscated.orElse(false);
    }

    /**
     * Resolves the {@link MinecraftFont.Style} corresponding to this segment's bold and italic
     * flags. Used by {@code TextRenderer} and any other caller that needs to pick a font
     * variant from a styled segment without coupling the font enum back to this class.
     *
     * @return the matching font style, or {@link MinecraftFont.Style#REGULAR} when neither
     * bold nor italic is set
     */
    public @NotNull MinecraftFont.Style fontStyle() {
        return MinecraftFont.Style.of((this.isBold() ? 1 : 0) + (this.isItalic() ? 2 : 0));
    }

    /**
     * Returns the text this segment and its siblings show, unresolved and without style.
     *
     * @return the plain text
     */
    public @NotNull String toPlainText() {
        StringBuilder builder = new StringBuilder(this.getText());
        this.extra.forEach(sibling -> builder.append(sibling.toPlainText()));
        return builder.toString();
    }

    /**
     * Explode the {@link #getText()} into single-words for use in a dynamic newline system, each word in
     * this segment's style and without its siblings.
     */
    public @NotNull ConcurrentList<TextSegment> explode() {
        return Arrays.stream(StringUtil.split(this.getText(), " "))
            .map(this::run)
            .collect(Concurrent.toList());
    }

    /**
     * Flattens this segment and its siblings into lines of runs - each run plain text in the style it is
     * shown in, inherited style resolved, and a new line begun at each {@code \n}.
     *
     * @return the lines, at least one
     */
    public @NotNull ConcurrentList<LineSegment> toLines() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        List<TextSegment> line = new ArrayList<>();

        this.flatten(null, run -> {
            String[] pieces = run.getText().split("\n", -1);

            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) {
                    lines.add(LineSegment.builder().segments(Concurrent.newList(line)).build());
                    line.clear();
                }

                if (!pieces[i].isEmpty())
                    line.add(run.run(pieces[i]));
            }
        });

        lines.add(LineSegment.builder().segments(Concurrent.newList(line)).build());
        return lines;
    }

    /**
     * Writes this segment and its siblings as a legacy string coded with {@link ChatFormat#SECTION_SYMBOL}.
     *
     * @return the legacy string
     * @see #toLegacy(char)
     */
    public @NotNull String toLegacy() {
        return this.toLegacy(ChatFormat.SECTION_SYMBOL);
    }

    /**
     * Writes this segment and its siblings as a legacy string - each run's text preceded by the codes that
     * move the legacy style from the previous run's to its own: its colour code, or {@code r} where it has
     * no colour a legacy code can name, whenever the colour changes or a format has to be turned off, since
     * either resets every format, and then the code of each format it turns on.
     *
     * @param symbol the character the codes are introduced by
     * @return the legacy string
     */
    public @NotNull String toLegacy(char symbol) {
        StringBuilder builder = new StringBuilder();
        Character[] color = { null };
        EnumSet<ChatFormat> formats = EnumSet.noneOf(ChatFormat.class);

        this.flatten(null, run -> {
            if (run.getText().isEmpty())
                return;

            Character runColor = run.color.flatMap(ChatColor::code).orElse(null);
            EnumSet<ChatFormat> runFormats = run.legacyFormats();

            if (!Objects.equals(runColor, color[0]) || !runFormats.containsAll(formats)) {
                builder.append(symbol).append(runColor != null ? runColor : ChatFormat.RESET.getCode());
                color[0] = runColor;
                formats.clear();
            }

            for (ChatFormat format : runFormats) {
                if (formats.add(format))
                    builder.append(symbol).append(format.getCode());
            }

            builder.append(run.getText());
        });

        return builder.toString();
    }

    /**
     * Writes this segment as vanilla writes a text component, plus a {@code gradient} member where it has
     * one.
     *
     * @return a JSON string where the segment is plain text in no style and without siblings, and an
     *     object otherwise
     */
    public @NotNull JsonElement toJson() {
        if (this.collapsesToString())
            return new JsonPrimitive(this.getText());

        JsonObject object = new JsonObject();
        this.contents.write(object);

        if (!this.extra.isEmpty()) {
            JsonArray siblings = new JsonArray(this.extra.size());
            this.extra.forEach(sibling -> siblings.add(sibling.toJson()));
            object.add("extra", siblings);
        }

        this.color.ifPresent(color -> object.addProperty("color", color.toJsonString()));
        this.shadowColor.ifPresent(shadowColor -> object.addProperty("shadow_color", shadowColor.rgb()));
        this.bold.ifPresent(bold -> object.addProperty("bold", bold));
        this.italic.ifPresent(italic -> object.addProperty("italic", italic));
        this.underlined.ifPresent(underlined -> object.addProperty("underlined", underlined));
        this.strikethrough.ifPresent(strikethrough -> object.addProperty("strikethrough", strikethrough));
        this.obfuscated.ifPresent(obfuscated -> object.addProperty("obfuscated", obfuscated));
        this.clickEvent.ifPresent(clickEvent -> object.add("click_event", clickEvent.toJson()));
        this.hoverEvent.ifPresent(hoverEvent -> object.add("hover_event", hoverEvent.toJson()));
        this.insertion.ifPresent(insertion -> object.addProperty("insertion", insertion));
        this.font.ifPresent(font -> object.addProperty("font", font.toString()));
        this.gradient.ifPresent(gradient -> object.add("gradient", gradient.toJson()));
        return object;
    }

    /**
     * Parses JSON text and reads it as a text component.
     *
     * @param json the JSON text
     * @return the segment
     * @throws TextJsonException if the text is no JSON, or no text component vanilla reads
     * @see #fromJson(JsonElement)
     */
    public static @NotNull TextSegment fromJson(@NotNull String json) {
        try {
            return fromJson(JsonParser.parseString(json));
        } catch (JsonParseException exception) {
            throw new TextJsonException(exception, "Malformed JSON text component");
        }
    }

    /**
     * Reads a text component as vanilla reads one - a JSON string as plain text, a non-empty list as its
     * first element with the rest appended to its siblings, and an object as its contents, siblings and
     * style.
     *
     * @param json the component's JSON
     * @return the segment
     * @throws TextJsonException if the JSON is no text component vanilla reads
     */
    public static @NotNull TextSegment fromJson(@NotNull JsonElement json) {
        if (json instanceof JsonPrimitive primitive && primitive.isString())
            return literal(primitive.getAsString());

        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();

            if (array.isEmpty())
                throw new TextJsonException("Expected a text component list to hold a component");

            TextSegment first = fromJson(array.get(0));
            List<TextSegment> extra = new ArrayList<>(first.extra);

            for (int i = 1; i < array.size(); i++)
                extra.add(fromJson(array.get(i)));

            return first.mutate().extra(List.copyOf(extra)).build();
        }

        if (!json.isJsonObject())
            throw new TextJsonException("Expected a text component, found '%s'", json);

        JsonObject object = json.getAsJsonObject();

        return builder()
            .contents(TextContents.read(object))
            .extra(siblings(object))
            .color(TextJson.member(object, "color").map(TextSegment::color))
            .shadowColor(TextJson.member(object, "shadow_color").map(TextSegment::shadowColor))
            .bold(flag(object, "bold"))
            .italic(flag(object, "italic"))
            .underlined(flag(object, "underlined"))
            .strikethrough(flag(object, "strikethrough"))
            .obfuscated(flag(object, "obfuscated"))
            .clickEvent(TextJson.member(object, "click_event").map(ClickEvent::fromJson))
            .hoverEvent(TextJson.member(object, "hover_event").map(HoverEvent::fromJson))
            .insertion(TextJson.member(object, "insertion").map(insertion -> TextJson.string(insertion, "insertion")))
            .font(TextJson.member(object, "font").map(font -> FontId.parse(TextJson.identifier(font, "font"))))
            .gradient(TextJson.member(object, "gradient").map(TextSegment::gradient))
            .build();
    }

    /**
     * Reads legacy text coded with {@code &} or {@link ChatFormat#SECTION_SYMBOL} into a line of runs.
     *
     * @param legacyText the legacy text
     * @return the line
     * @see #fromLegacy(String, char)
     */
    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText) {
        return fromLegacy(legacyText, '&');
    }

    /**
     * This function takes in a legacy text string and converts it into a line of {@link TextSegment} runs.
     * <p>
     * Legacy text strings use the {@link ChatFormat#SECTION_SYMBOL}. Many keyboards do not have this symbol however,
     * which is probably why it was chosen. To get around this, it is common practice to substitute
     * the symbol for another, then translate it later. Often '&' is used, but this can differ from person
     * to person. In case the string does not have a {@link ChatFormat#SECTION_SYMBOL}, the method also checks for the
     * {@code symbolSubstitute}.
     * <p>
     * A colour code starts a run in that colour with no format; a format code turns that format on for the
     * run it starts; {@code r} starts a white run with no format. A code is matched as written, so an
     * upper-case letter or a character that is no code stays in the text along with the symbol before it.
     * Each run's text takes the substitution {@link Builder#text(String)} applies.
     *
     * @param legacyText The text to make into an object
     * @param symbolSubstitute The character substitute
     * @return A LineSegment representing the legacy text
     */
    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText, char symbolSubstitute) {
        LineSegment.Builder line = LineSegment.builder();
        Builder current = builder();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < legacyText.length(); i++) {
            char ch = legacyText.charAt(i);

            if ((ch != ChatFormat.SECTION_SYMBOL && ch != symbolSubstitute) || i + 1 >= legacyText.length()) {
                buf.append(ch);
                continue;
            }

            char peek = legacyText.charAt(++i);

            // Try color first, then format
            ChatColor color = ChatColor.of(peek);
            ChatFormat format = color == null ? ChatFormat.ofCode(peek) : null;

            if (color == null && format == null) {
                buf.append(ch);
                i--; // un-consume the peek
                continue;
            }

            // Flush buffered text before applying the new code
            if (!buf.isEmpty()) {
                line.addSegment(current.text(buf.toString()).build());
                current = builder();
                buf.setLength(0);
            }

            if (color != null) {
                // Color codes reset all styles (vanilla behavior)
                current = builder().color(color);
            } else if (format == ChatFormat.RESET) {
                current.color(ChatColor.Legacy.WHITE)
                    .obfuscated(Optional.empty())
                    .bold(Optional.empty())
                    .italic(Optional.empty())
                    .underlined(Optional.empty())
                    .strikethrough(Optional.empty());
            } else {
                switch (format) {
                    case OBFUSCATED -> current.obfuscated(true);
                    case BOLD -> current.bold(true);
                    case STRIKETHROUGH -> current.strikethrough(true);
                    case UNDERLINE -> current.underlined(true);
                    case ITALIC -> current.italic(true);
                    default -> {}
                }
            }
        }

        line.addSegment(current.text(buf.toString()).build());
        return line.build();
    }

    /**
     * Returns whether vanilla writes this segment as a plain string - plain text in no style and without
     * siblings.
     *
     * @return whether the segment collapses to its text
     */
    boolean collapsesToString() {
        return this.contents instanceof TextContents.Text
            && this.extra.isEmpty()
            && this.color.isEmpty()
            && this.gradient.isEmpty()
            && this.shadowColor.isEmpty()
            && this.bold.isEmpty()
            && this.italic.isEmpty()
            && this.underlined.isEmpty()
            && this.strikethrough.isEmpty()
            && this.obfuscated.isEmpty()
            && this.clickEvent.isEmpty()
            && this.hoverEvent.isEmpty()
            && this.insertion.isEmpty()
            && this.font.isEmpty();
    }

    /**
     * The colour's value, which is what two segments' colours compare by.
     */
    @EqualsInclude
    private @NotNull Optional<Integer> colorValue() {
        return this.color.map(color -> color.rgb() & 0xFFFFFF);
    }

    /**
     * The shadow colour's ARGB value, which is what two segments' shadow colours compare by.
     */
    @EqualsInclude
    private @NotNull Optional<Integer> shadowColorValue() {
        return this.shadowColor.map(ChatColor::rgb);
    }

    /**
     * Visits the runs this segment and its siblings show, in order, each in the style it inherits from
     * {@code parent} where it sets none of its own.
     */
    private void flatten(@Nullable TextSegment parent, @NotNull Consumer<TextSegment> runs) {
        TextSegment styled = parent == null ? this : this.inherit(parent);

        switch (this.contents) {
            case TextContents.Text text -> runs.accept(styled.run(text.text()));
            case TextContents.Translatable translatable -> translatable.decompose().forEach(part -> part.flatten(styled, runs));
            case TextContents.Sprite sprite when sprite.fallback().isPresent() -> sprite.fallback().get().flatten(styled, runs);
            default -> runs.accept(styled.run(this.contents.plainText()));
        }

        this.extra.forEach(sibling -> sibling.flatten(styled, runs));
    }

    /**
     * Returns this segment with each style field it does not set taken from {@code parent}, as vanilla
     * applies a style to the one it inherits.
     */
    private @NotNull TextSegment inherit(@NotNull TextSegment parent) {
        return this.mutate()
            .color(this.color.or(() -> parent.color))
            .gradient(this.gradient.or(() -> parent.gradient))
            .shadowColor(this.shadowColor.or(() -> parent.shadowColor))
            .bold(this.bold.or(() -> parent.bold))
            .italic(this.italic.or(() -> parent.italic))
            .underlined(this.underlined.or(() -> parent.underlined))
            .strikethrough(this.strikethrough.or(() -> parent.strikethrough))
            .obfuscated(this.obfuscated.or(() -> parent.obfuscated))
            .clickEvent(this.clickEvent.or(() -> parent.clickEvent))
            .hoverEvent(this.hoverEvent.or(() -> parent.hoverEvent))
            .insertion(this.insertion.or(() -> parent.insertion))
            .font(this.font.or(() -> parent.font))
            .build();
    }

    /**
     * Returns a run of plain text in this segment's style, without siblings.
     */
    private @NotNull TextSegment run(@NotNull String text) {
        return this.mutate().contents(new TextContents.Text(text)).extra(List.of()).build();
    }

    /**
     * Returns the legacy formats this segment is drawn with.
     */
    private @NotNull EnumSet<ChatFormat> legacyFormats() {
        EnumSet<ChatFormat> formats = EnumSet.noneOf(ChatFormat.class);
        if (this.isObfuscated()) formats.add(ChatFormat.OBFUSCATED);
        if (this.isBold()) formats.add(ChatFormat.BOLD);
        if (this.isStrikethrough()) formats.add(ChatFormat.STRIKETHROUGH);
        if (this.isUnderlined()) formats.add(ChatFormat.UNDERLINE);
        if (this.isItalic()) formats.add(ChatFormat.ITALIC);
        return formats;
    }

    private static @NotNull List<TextSegment> siblings(@NotNull JsonObject object) {
        Optional<JsonElement> extra = TextJson.member(object, "extra");

        if (extra.isEmpty())
            return List.of();

        if (!extra.get().isJsonArray())
            throw new TextJsonException("Expected 'extra' to be a list, found '%s'", extra.get());

        JsonArray array = extra.get().getAsJsonArray();

        if (array.isEmpty())
            throw new TextJsonException("Expected 'extra' to hold a component");

        List<TextSegment> siblings = new ArrayList<>(array.size());

        for (JsonElement element : array)
            siblings.add(fromJson(element));

        return List.copyOf(siblings);
    }

    private static @NotNull ChatColor color(@NotNull JsonElement element) {
        String value = TextJson.string(element, "color");
        ChatColor color = ChatColor.fromJsonString(value);

        if (color == null)
            throw new TextJsonException("Invalid color '%s'", value);

        return color;
    }

    /**
     * Reads a shadow colour as vanilla does - packed ARGB as a number, or {@code [r, g, b, a]} channels
     * from {@code 0} to {@code 1}, each taken as {@code floor(channel * 255)} - into a colour that keeps
     * its alpha.
     */
    private static @NotNull ChatColor shadowColor(@NotNull JsonElement element) {
        return ChatColor.builder().color(new Color(shadowArgb(element), true)).build();
    }

    /**
     * Reads a shadow colour's packed ARGB value.
     */
    private static int shadowArgb(@NotNull JsonElement element) {
        if (!element.isJsonArray())
            return TextJson.integer(element, "shadow_color");

        JsonArray channels = element.getAsJsonArray();

        if (channels.size() != 4)
            throw new TextJsonException("Expected 'shadow_color' to hold 4 channels, found '%s'", element);

        Function<Integer, Integer> channel = index -> {
            float scaled = TextJson.number(channels.get(index), "shadow_color").floatValue() * 255.0F;
            int whole = (int) scaled;
            return (scaled < whole ? whole - 1 : whole) & 0xFF;
        };

        return channel.apply(3) << 24 | channel.apply(0) << 16 | channel.apply(1) << 8 | channel.apply(2);
    }

    private static @NotNull Optional<Boolean> flag(@NotNull JsonObject object, @NotNull String key) {
        return TextJson.member(object, key).map(flag -> TextJson.bool(flag, key));
    }

    private static @NotNull GradientSpec gradient(@NotNull JsonElement element) {
        try {
            return GradientSpec.fromJson(TextJson.object(element, "gradient"));
        } catch (IllegalArgumentException exception) {
            throw new TextJsonException(exception, "Invalid gradient '%s'", element);
        }
    }

    /**
     * Rewrites apostrophes as the legacy reader and {@link Builder#text(String)} take them - each
     * unescaped {@code '} as {@code ’} and each {@code \'} as {@code '}.
     */
    private static @NotNull String normalizeApostrophes(@NotNull String text) {
        return StringUtil.defaultIfEmpty(text, "")
            .replaceAll("(?<!\\\\)'", "’") // Handle Unescaped Windows Apostrophe
            .replaceAll("\\\\'", "'"); // Remove Escaped Backslash
    }

    /**
     * Builder for a {@link TextSegment}.
     */
    public static class Builder {

        /**
         * Sets the contents to literal text, each unescaped {@code '} rewritten as {@code ’} and each
         * {@code \'} as {@code '}, as the legacy reader rewrites them. Use
         * {@link TextContents.Text} through {@code contents} to keep the text exactly as given.
         *
         * @param text the text
         * @return this builder
         */
        public @NotNull Builder text(@NotNull String text) {
            return this.contents(new TextContents.Text(normalizeApostrophes(text)));
        }

    }

}
