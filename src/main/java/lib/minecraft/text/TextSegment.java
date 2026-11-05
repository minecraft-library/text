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
 * {@link LineSegment lines} of runs, and {@link #fromLegacy(String, char)} reads section-coded text
 * straight into a line of them. Each run is plain text in the style it is shown in, and a line is cut
 * into runs wherever the style changes, as the client's font cuts one by its {@code Style#equals} - the
 * colour by its value, the shadow colour by its ARGB value, each flag three-state, the click and hover
 * events, the insertion and the font. Neighbours in one style are one run and empty text is no run and
 * cuts none. Three things keep two neighbours in one style apart: a gradient on either side, so each
 * gradient spreads over its own run; the edge of a sprite, which the client draws as a run of its own in
 * the sprite's font; and a lone high surrogate ending one where a lone low surrogate opens the other,
 * which the client draws as two replacement characters rather than one codepoint. A run therefore opens
 * where the client's font opens one, which is where the client leads an underline or strikethrough one
 * mcPixel left of the first glyph. The client also cuts a line it reorders for right-to-left text at
 * each visual run, and no such reordering is modelled here.</p>
 *
 * <p>A component keeps a section sign ({@link ChatFormat#SECTION_SYMBOL}) and the character after it in
 * its text as written, as the client's component does, and the client reads the two, wherever they fall in
 * a text it draws, as a code that restyles the rest of that text. The runs {@link #toLines()} and
 * {@link #fromLegacy(String, char)} answer are read the same way, so they hold no legacy code and neither
 * character is drawn. A code ends the run before it, and the run after it joins that one again as any
 * neighbour in its style does, so a gradient, which keeps two runs apart, restarts at each code in its
 * text, even one that leaves the style as it was.</p>
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
     * Returns the text this segment holds on its own, before its siblings and unresolved, as written - a
     * legacy code in it included, as the client's {@code Component#getString} keeps one. A run
     * {@link #toLines()} or {@link #fromLegacy(String, char)} answers holds none.
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
     * flags - the style {@link #resolveFont()} picks the segment's font in.
     *
     * @return the matching font style, or {@link MinecraftFont.Style#REGULAR} when neither
     * bold nor italic is set
     */
    public @NotNull MinecraftFont.Style fontStyle() {
        return MinecraftFont.Style.of((this.isBold() ? 1 : 0) + (this.isItalic() ? 2 : 0));
    }

    /**
     * Resolves the font this segment is drawn in - the one it names, or {@link FontId#DEFAULT} where
     * it names none, in its {@link #fontStyle() style} - as
     * {@link MinecraftFont#of(FontId, MinecraftFont.Style)} answers it. A run {@link #toLines()} gives
     * carries the font it inherits, and an id the library draws no font for answers
     * {@link MinecraftFont.Missing}.
     *
     * @return the font the segment is drawn in
     */
    public @NotNull MinecraftFont resolveFont() {
        return MinecraftFont.of(this.font.orElse(FontId.DEFAULT), this.fontStyle());
    }

    /**
     * Returns the text this segment and its siblings hold, unresolved and without their style fields, as
     * written - a legacy code in it included, as the client's {@code Component#getString} keeps one.
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
     * shown in, inherited style resolved, the legacy codes in its text read, and a new line begun at each
     * {@code \n}. Neighbours in one style on a line are one run and empty text is none, as the client's font
     * cuts a line, save where a gradient on either side, the edge of a sprite or a surrogate pair split
     * between them keeps the two apart.
     *
     * <p>The codes are read as the client's font reads them in a text it draws: a code restyles the rest of
     * the text it sits in and no other component's, a reset returns to the style that text is shown in, a
     * code's style carries past a {@code \n} in the same text, and a sign directly before a {@code \n} takes
     * it as its code, so no line begins there. Where the client wraps a text before it draws it, as it wraps
     * a book page, its line splitter reads the codes as it wraps, a reset as no style at all, and opens the
     * text after each break in the style in force there. The lines answered here hold no code, so they
     * cannot be wrapped as the client wraps a page, which it wraps from the page's component with the codes
     * still in its text.</p>
     *
     * @return the lines, at least one
     */
    public @NotNull ConcurrentList<LineSegment> toLines() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        List<TextSegment> line = new ArrayList<>();
        int[] joinsFrom = { 0 };

        this.flatten(null, run -> {
            String[] pieces = run.getText().split("\n", -1);

            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) {
                    lines.add(LineSegment.builder().segments(Concurrent.newList(line)).build());
                    line.clear();
                    joinsFrom[0] = 0;
                }

                appendRun(line, run.run(pieces[i]), joinsFrom[0]);
            }
        }, () -> joinsFrom[0] = line.size());

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
        }, () -> {});

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
     * Reads legacy text coded with {@link ChatFormat#SECTION_SYMBOL}, or with {@code &} where a code character
     * follows it, into a line of runs.
     *
     * @param legacyText the legacy text
     * @return the line
     * @see #fromLegacy(String, char)
     */
    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText) {
        return fromLegacy(legacyText, '&');
    }

    /**
     * Reads legacy text into a line of runs, from no style, as the client's font reads the legacy codes in a
     * text it draws.
     *
     * <p>A section sign ({@link ChatFormat#SECTION_SYMBOL}) and the character after it are a code, and neither is
     * drawn. The character, in its lower case, names a colour, which the code sets and turns every format off
     * with; a format, which it turns on over the colour and the formats already on; or {@code r}, which returns
     * to no style. Any other character is dropped with the sign before it, and a sign ending the text is
     * dropped.</p>
     *
     * <p>{@code symbolSubstitute} is a stand-in for the section sign, which few keyboards have: it reads as the
     * sign where a code character follows it as written - {@code 0} to {@code 9}, {@code a} to {@code f},
     * {@code k} to {@code o} or {@code r} - and is text anywhere else, as the client keeps as text every
     * character it reads no code in.</p>
     *
     * <p>Each run's text takes the substitution {@link Builder#text(String)} applies. A run in the style of the
     * run before it joins that run and a code no text follows starts none, as {@link #toLines()} answers a
     * line's runs.</p>
     *
     * @param legacyText the legacy text
     * @param symbolSubstitute the character that stands for the section sign before a code
     * @return the line
     */
    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText, char symbolSubstitute) {
        List<TextSegment> runs = new ArrayList<>();
        builder().build().readCodes(legacyText, symbolSubstitute,
            run -> appendRun(runs, run.mutate().text(run.getText()).build(), 0));
        return LineSegment.builder().segments(Concurrent.newList(runs)).build();
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
     * {@code parent} where it sets none of its own and with the legacy codes in its text read, and runs
     * {@code cut} on either side of a sprite's runs, since the client draws a sprite as a run of its own in the
     * sprite's font.
     *
     * @param parent the segment this one inherits its style from, or {@code null} for none
     * @param runs the visitor of each run
     * @param cut the action run on either side of a sprite's runs
     */
    private void flatten(@Nullable TextSegment parent, @NotNull Consumer<TextSegment> runs, @NotNull Runnable cut) {
        TextSegment styled = parent == null ? this : this.inherit(parent);

        switch (this.contents) {
            case TextContents.Text text -> styled.readCodes(text.text(), ChatFormat.SECTION_SYMBOL, runs);
            case TextContents.Translatable translatable -> translatable.decompose().forEach(part -> part.flatten(styled, runs, cut));
            case TextContents.Sprite sprite -> {
                cut.run();
                sprite.fallback().ifPresentOrElse(
                    fallback -> fallback.flatten(styled, runs, cut),
                    () -> styled.readCodes(sprite.plainText(), ChatFormat.SECTION_SYMBOL, runs)
                );
                cut.run();
            }
            default -> styled.readCodes(this.contents.plainText(), ChatFormat.SECTION_SYMBOL, runs);
        }

        this.extra.forEach(sibling -> sibling.flatten(styled, runs, cut));
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
     * Visits the runs a text shown in this segment's style is drawn as, reading each legacy code in it as
     * the client's font reads one: a section sign and the character after it restyle the rest of the text,
     * and neither is drawn, so each run holds the text between two codes in the style the codes before it
     * leave. The style opens as this segment's own, which a reset returns to. A character that is no code is
     * dropped with the sign before it, a sign ending the text ends it, and a run is handed on at every code,
     * so a code that parts the two halves of a surrogate pair leaves them two runs.
     *
     * @param text the text
     * @param symbolSubstitute a character read as the section sign where a code character follows it as
     *     written - the section sign itself for text that has no substitute
     * @param runs the visitor of each run, empty ones included
     */
    private void readCodes(@NotNull String text, char symbolSubstitute, @NotNull Consumer<TextSegment> runs) {
        TextSegment style = this;
        StringBuilder piece = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean sign = ch == ChatFormat.SECTION_SYMBOL
                || ch == symbolSubstitute && i + 1 < text.length() && ChatFormat.isValid(text.charAt(i + 1));

            if (!sign) {
                piece.append(ch);
                continue;
            }

            runs.accept(style.run(piece.toString()));
            piece.setLength(0);

            if (++i == text.length())
                break;

            style = style.applyLegacyCode(text.charAt(i), this);
        }

        runs.accept(style.run(piece.toString()));
    }

    /**
     * Returns this style with one legacy code applied, the character read in its lower case as the client
     * reads a code: a colour code sets the colour and turns all five formats off, as the client's
     * {@code Style#applyLegacyFormat} sets them false, a format code turns its one format on, and a reset
     * answers {@code reset}. Any other character leaves the style as it is. Everything else the style holds -
     * the shadow colour, the click and hover events, the insertion, the font and the gradient - is kept.
     *
     * @param code the character after the section sign
     * @param reset the style a reset returns to
     * @return the style the code leaves
     */
    private @NotNull TextSegment applyLegacyCode(char code, @NotNull TextSegment reset) {
        char lower = Character.toLowerCase(code);
        ChatColor color = ChatColor.of(lower);

        if (color != null) {
            return this.mutate()
                .color(color)
                .bold(false)
                .italic(false)
                .underlined(false)
                .strikethrough(false)
                .obfuscated(false)
                .build();
        }

        ChatFormat format = ChatFormat.ofCode(lower);

        if (format == null)
            return this;

        return switch (format) {
            case OBFUSCATED -> this.mutate().obfuscated(true).build();
            case BOLD -> this.mutate().bold(true).build();
            case STRIKETHROUGH -> this.mutate().strikethrough(true).build();
            case UNDERLINE -> this.mutate().underlined(true).build();
            case ITALIC -> this.mutate().italic(true).build();
            case RESET -> reset;
        };
    }

    /**
     * Returns whether {@code next} continues this run where it follows it, as the client's font keeps
     * one run while its {@code Style#equals} holds between two neighbouring characters - the colour by
     * its value, the shadow colour by its ARGB value, each flag three-state, the click and hover events,
     * the insertion and the font - and neither carries a gradient, which spreads over its own run.
     *
     * @param next the run following this one
     * @return whether the two are one run
     */
    private boolean joins(@NotNull TextSegment next) {
        return this.gradient.isEmpty()
            && next.gradient.isEmpty()
            && this.colorValue().equals(next.colorValue())
            && this.shadowColorValue().equals(next.shadowColorValue())
            && this.bold.equals(next.bold)
            && this.italic.equals(next.italic)
            && this.underlined.equals(next.underlined)
            && this.strikethrough.equals(next.strikethrough)
            && this.obfuscated.equals(next.obfuscated)
            && this.clickEvent.equals(next.clickEvent)
            && this.hoverEvent.equals(next.hoverEvent)
            && this.insertion.equals(next.insertion)
            && this.font.equals(next.font);
    }

    /**
     * Ends a line's runs with {@code run} as the client's font cuts a line: empty text is no run, and a run
     * the last one {@link #joins(TextSegment) joins} extends it by its text, in the last one's style, as the
     * client's run takes the style of its first character. The last run is not extended where it sits
     * before {@code joinsFrom}, as the edge of a sprite leaves it, or where the join would fuse a lone high
     * surrogate ending it and a lone low surrogate opening {@code run} into one codepoint, since the client
     * draws each half as a replacement character.
     *
     * @param runs the line's runs so far
     * @param run the plain-text run following them
     * @param joinsFrom the index of the first run a later one may extend
     */
    private static void appendRun(@NotNull List<TextSegment> runs, @NotNull TextSegment run, int joinsFrom) {
        String text = run.getText();

        if (text.isEmpty())
            return;

        TextSegment last = runs.size() > joinsFrom ? runs.getLast() : null;
        String lastText = last == null ? "" : last.getText();
        boolean splitsPair = !lastText.isEmpty()
            && Character.isHighSurrogate(lastText.charAt(lastText.length() - 1))
            && Character.isLowSurrogate(text.charAt(0));

        if (last != null && !splitsPair && last.joins(run))
            runs.set(runs.size() - 1, last.run(lastText + text));
        else
            runs.add(run);
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
