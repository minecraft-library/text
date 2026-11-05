package lib.minecraft.renderer.text;

import com.google.gson.JsonParser;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Covers the runs {@link TextSegment#toLines()} and {@link TextSegment#fromLegacy(String, char)} answer -
 * the client's style runs, which its font cuts wherever {@code Style#equals} fails between two
 * neighbouring characters and opens each at position {@code 0}. Neighbours in one style join into one
 * run, a neighbour differing in any style field the client compares stays a run of its own, empty text
 * cuts nothing, a gradient, the edge of a sprite and a split surrogate pair keep two runs apart, and a
 * legacy format code adds to the style already on. Each section sign and the character after it in a text
 * is read as the client's font reads a legacy code, and neither is left in a run's text.
 */
class TextSegmentTest {

    /**
     * A gradient for the cases that carry one.
     */
    private static final GradientSpec GRADIENT = GradientSpec.builder(GradientSpec.Mode.START_END)
        .addStop(0xFF0000)
        .addStop(0x0000FF)
        .build();

    @Test
    @DisplayName("legacy neighbours in one style are one run")
    void legacyNeighboursInOneStyleAreOneRun() {
        List<TextSegment> runs = TextSegment.fromLegacy("&7&nAB&7&nCD").getSegments();
        List<LineSegment> lines = LineSegment.fromLegacy("&7A&7B\n&lC&lD\n&8E&8F&8G", '&');

        assertAll(
            () -> assertThat(texts(runs), contains("ABCD")),
            () -> assertThat(runs.getFirst().getColor(), is(Optional.of(ChatColor.Legacy.GRAY))),
            () -> assertThat(runs.getFirst().isUnderlined(), is(true)),
            () -> assertThat("three runs", texts(TextSegment.fromLegacy("&7A&7B&7C").getSegments()), contains("ABC")),
            () -> assertThat("each line of LineSegment.fromLegacy", lines.stream().map(line -> texts(line.getSegments())).toList(), contains(List.of("AB"), List.of("CD"), List.of("EFG")))
        );
    }

    @Test
    @DisplayName("a legacy format code keeps the colour and formats already on, as the client applies one")
    void aLegacyFormatCodeKeepsTheStyleOn() {
        List<TextSegment> again = TextSegment.fromLegacy("&7&nAB&nCD").getSegments();
        List<TextSegment> bold = TextSegment.fromLegacy("&7A&lB").getSegments();
        List<TextSegment> both = TextSegment.fromLegacy("&lA&nB").getSegments();
        TextSegment written = TextSegment.builder().extra(List.of(
            TextSegment.builder().text("A").color(ChatColor.Legacy.GRAY).build(),
            TextSegment.builder().text("B").color(ChatColor.Legacy.GRAY).bold(true).build()
        )).build();

        assertAll(
            () -> assertThat("a format already on", texts(again), contains("ABCD")),
            () -> assertThat("a format already on keeps its colour", again.getFirst().getColor(), is(Optional.of(ChatColor.Legacy.GRAY))),
            () -> assertThat("a format turned on after text", texts(bold), contains("A", "B")),
            () -> assertThat("keeps the colour", bold.getLast().getColor(), is(Optional.of(ChatColor.Legacy.GRAY))),
            () -> assertThat("and turns its format on", bold.getLast().isBold(), is(true)),
            () -> assertThat("keeps the formats already on", List.of(both.getLast().isBold(), both.getLast().isUnderlined()), contains(true, true)),
            () -> assertThat("a colour code turns every format off", TextSegment.fromLegacy("&7&lA&7B").getSegments().getLast().isBold(), is(false)),
            () -> assertThat("toLegacy's own text reads back to what its runs draw",
                drawn(TextSegment.fromLegacy(written.toLegacy()).getSegments()),
                is(drawn(written.toLines().getFirst().getSegments())))
        );
    }

    @Test
    @DisplayName("JSON siblings in one style are one run, whether the style is spelled out or inherited")
    void jsonSiblingsInOneStyleAreOneRun() {
        assertAll(
            () -> assertThat("spelled out", texts(runs("[\"\", {\"text\": \"AB\", \"color\": \"gray\", \"underlined\": true}, {\"text\": \"CD\", \"color\": \"gray\", \"underlined\": true}]")), contains("ABCD")),
            () -> assertThat("inherited", texts(runs("{\"text\": \"AB\", \"color\": \"red\", \"extra\": [\"CD\", {\"text\": \"EF\"}]}")), contains("ABCDEF")),
            () -> assertThat("inherited and spelled out", texts(runs("{\"text\": \"AB\", \"bold\": true, \"extra\": [{\"text\": \"CD\", \"bold\": true}]}")), contains("ABCD")),
            () -> assertThat("a translation and its arguments", texts(runs("{\"translate\": \"%s and %s\", \"with\": [\"A\", \"B\"], \"color\": \"gray\"}")), contains("A and B"))
        );
    }

    @Test
    @DisplayName("a colour compares by its value, and the joined run keeps the first run's colour")
    void aColourComparesByItsValue() {
        List<TextSegment> runs = runs("[\"\", {\"text\": \"AB\", \"color\": \"red\"}, {\"text\": \"CD\", \"color\": \"#FF5555\"}]");

        assertAll(
            () -> assertThat(texts(runs), contains("ABCD")),
            () -> assertThat(runs.getFirst().toJson(), is(JsonParser.parseString("{\"text\": \"ABCD\", \"color\": \"red\"}")))
        );
    }

    @Test
    @DisplayName("neighbours differing in any one style field the client compares are two runs")
    void aStyleFieldThatDiffersKeepsTwoRuns() {
        Map<String, String[]> differing = new LinkedHashMap<>();
        differing.put("color", new String[] { "\"color\": \"red\"", "\"color\": \"blue\"" });
        differing.put("color against none", new String[] { "\"color\": \"white\"", "" });
        differing.put("shadow_color", new String[] { "\"shadow_color\": -16777216", "\"shadow_color\": -16776961" });
        differing.put("shadow_color alpha alone", new String[] { "\"shadow_color\": -16777216", "\"shadow_color\": 2130706432" });
        differing.put("bold", new String[] { "\"bold\": true", "" });
        differing.put("italic", new String[] { "\"italic\": true", "" });
        differing.put("underlined", new String[] { "\"underlined\": true", "" });
        differing.put("strikethrough", new String[] { "\"strikethrough\": true", "" });
        differing.put("obfuscated", new String[] { "\"obfuscated\": true", "" });
        differing.put("click_event", new String[] { "\"click_event\": {\"action\": \"run_command\", \"command\": \"/a\"}", "\"click_event\": {\"action\": \"run_command\", \"command\": \"/b\"}" });
        differing.put("hover_event", new String[] { "\"hover_event\": {\"action\": \"show_text\", \"value\": \"a\"}", "\"hover_event\": {\"action\": \"show_text\", \"value\": \"b\"}" });
        differing.put("insertion", new String[] { "\"insertion\": \"a\"", "\"insertion\": \"b\"" });
        differing.put("insertion empty against none", new String[] { "\"insertion\": \"\"", "" });
        differing.put("font", new String[] { "\"font\": \"minecraft:alt\"", "\"font\": \"minecraft:illageralt\"" });
        differing.put("the default font named against none", new String[] { "\"font\": \"minecraft:default\"", "" });

        assertAll(differing.entrySet().stream().map(field -> (Executable) () -> assertThat(
            field.getKey(),
            texts(runs("[\"\", " + sibling("AB", field.getValue()[0]) + ", " + sibling("CD", field.getValue()[1]) + "]")),
            contains("AB", "CD")
        )));
    }

    @Test
    @DisplayName("a flag set false and a flag left unset are two runs, as the client's three-state flags compare")
    void aFlagSetFalseIsNotAnUnsetFlag() {
        List<String> flags = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

        assertAll(flags.stream().map(flag -> (Executable) () -> assertAll(
            () -> assertThat(flag + " false against unset", texts(runs("[\"\", {\"text\": \"AB\", \"" + flag + "\": false}, {\"text\": \"CD\"}]")), contains("AB", "CD")),
            () -> assertThat(flag + " false inherited", texts(runs("{\"text\": \"AB\", \"" + flag + "\": false, \"extra\": [\"CD\"]}")), contains("ABCD"))
        )));
    }

    @Test
    @DisplayName("a run carrying a gradient never joins its neighbour, so each gradient spreads over its own run")
    void aGradientKeepsItsOwnRun() {
        TextSegment plain = TextSegment.literal("AB");
        TextSegment graded = TextSegment.builder().text("CD").gradient(GRADIENT).build();
        TextSegment gradedToo = TextSegment.builder().text("AB").gradient(GRADIENT).build();

        assertAll(
            () -> assertThat("both sides", texts(runs(gradedToo, graded)), contains("AB", "CD")),
            () -> assertThat("the right side", texts(runs(plain, graded)), contains("AB", "CD")),
            () -> assertThat("the left side", texts(runs(gradedToo, TextSegment.literal("CD"))), contains("AB", "CD")),
            () -> assertThat("inherited", texts(gradedToo.mutate().extra(List.of(TextSegment.literal("CD"))).build().toLines().getFirst().getSegments()), contains("AB", "CD"))
        );
    }

    @Test
    @DisplayName("empty text cuts no run and is no run, and a space is text")
    void emptyTextCutsNoRun() {
        List<TextSegment> space = TextSegment.fromLegacy("&f&n ").getSegments();

        assertAll(
            () -> assertThat("between two runs in one style", texts(runs("[\"\", {\"text\": \"AB\", \"color\": \"gray\"}, {\"text\": \"\", \"color\": \"gold\"}, {\"text\": \"CD\", \"color\": \"gray\"}]")), contains("ABCD")),
            () -> assertThat("a legacy code nothing follows", texts(TextSegment.fromLegacy("&7AB&n").getSegments()), contains("AB")),
            () -> assertThat("a legacy code alone", TextSegment.fromLegacy("&7").getSegments(), is(empty())),
            () -> assertThat("no legacy text", TextSegment.fromLegacy("").getSegments(), is(empty())),
            () -> assertThat("a space joins its neighbour", texts(runs("[\"\", {\"text\": \"AB\", \"color\": \"gray\", \"underlined\": true}, {\"text\": \" \", \"color\": \"gray\", \"underlined\": true}]")), contains("AB ")),
            () -> assertThat("a legacy space is a run", texts(space), contains(" ")),
            () -> assertThat("and carries its underline", space.getFirst().isUnderlined(), is(true))
        );
    }

    @Test
    @DisplayName("a sprite is cut from the runs beside it, as the client draws one in a font of its own")
    void aSpriteIsCutFromItsNeighbours() {
        assertAll(
            () -> assertThat("drawn as its fallback", texts(runs("[\"\", {\"text\": \"AB\", \"color\": \"gray\"}, {\"type\": \"object\", \"sprite\": \"item/apple\", \"fallback\": \"x\", \"color\": \"gray\"}, {\"text\": \"CD\", \"color\": \"gray\"}]")), contains("AB", "x", "CD")),
            () -> assertThat("drawn as its name", texts(runs("[\"\", \"AB\", {\"type\": \"object\", \"sprite\": \"item/apple\"}, \"CD\"]")), contains("AB", "[item/apple]", "CD")),
            () -> assertThat("with an empty fallback", texts(runs("[\"\", \"AB\", {\"type\": \"object\", \"sprite\": \"item/apple\", \"fallback\": \"\"}, \"CD\"]")), contains("AB", "CD")),
            () -> assertThat("its fallback's own runs join", texts(runs("[\"\", \"AB\", {\"type\": \"object\", \"sprite\": \"item/apple\", \"fallback\": [\"x\", \"y\"]}, \"CD\"]")), contains("AB", "xy", "CD"))
        );
    }

    @Test
    @DisplayName("a lone high surrogate and a lone low one stay two runs, as the client draws each as a replacement character")
    void aSplitSurrogatePairStaysTwoRuns() {
        assertAll(
            () -> assertThat("JSON siblings", texts(runs("[\"\", {\"text\": \"A\\uD83D\"}, {\"text\": \"\\uDE00B\"}]")), contains("A\uD83D", "\uDE00B")),
            () -> assertThat("legacy runs", texts(TextSegment.fromLegacy("&7A\uD83D&7\uDE00B").getSegments()), contains("A\uD83D", "\uDE00B")),
            () -> assertThat("a whole pair still joins", texts(runs("[\"\", {\"text\": \"A\\uD83D\\uDE00\"}, {\"text\": \"B\"}]")), contains("A\uD83D\uDE00B"))
        );
    }

    @Test
    @DisplayName("an obfuscated pair is one run, scrambled from the line's stream as the two were")
    void anObfuscatedPairIsOneRun() {
        List<TextSegment> runs = TextSegment.fromLegacy("&kAB&kCD").getSegments();
        MinecraftFont font = MinecraftFont.Vanilla.REGULAR;
        Random apart = new Random(7);

        assertAll(
            () -> assertThat(texts(runs), contains("ABCD")),
            () -> assertThat(runs.getFirst().isObfuscated(), is(true)),
            () -> assertThat(font.obfuscate("ABCD", new Random(7)), is(font.obfuscate("AB", apart) + font.obfuscate("CD", apart)))
        );
    }

    @Test
    @DisplayName("runs keep their text and order, and a run never joins across a line break")
    void runsKeepTheirTextAndOrder() {
        List<TextSegment> runs = runs("[\"\", {\"text\": \"Damage: \", \"color\": \"gray\"}, {\"text\": \"+1\", \"color\": \"red\"}, "
            + "{\"text\": \"00\", \"color\": \"red\"}, {\"text\": \" (\", \"color\": \"gray\"}, {\"text\": \"+20\", \"color\": \"gray\"}, "
            + "{\"text\": \")\", \"color\": \"dark_gray\"}]");
        List<LineSegment> lines = TextSegment.fromJson("{\"text\": \"AB\\nCD\", \"color\": \"gray\", \"extra\": [{\"text\": \"EF\\n\", \"color\": \"gray\"}, {\"text\": \"GH\", \"color\": \"gray\"}]}").toLines();

        assertAll(
            () -> assertThat(texts(runs), contains("Damage: ", "+100", " (+20", ")")),
            () -> assertThat(runs.stream().map(run -> run.getColor().orElseThrow()).toList(), contains(ChatColor.Legacy.GRAY, ChatColor.Legacy.RED, ChatColor.Legacy.GRAY, ChatColor.Legacy.DARK_GRAY)),
            () -> assertThat(lines.stream().map(line -> texts(line.getSegments())).toList(), contains(List.of("AB"), List.of("CDEF"), List.of("GH")))
        );
    }

    @Test
    @DisplayName("toJson keeps the siblings toLines joins")
    void toJsonKeepsTheSiblingsToLinesJoins() {
        TextSegment component = TextSegment.fromJson("[\"\", {\"text\": \"AB\", \"color\": \"gray\"}, {\"text\": \"CD\", \"color\": \"gray\"}]");

        assertAll(
            () -> assertThat(component.getExtra(), hasSize(2)),
            () -> assertThat(TextSegment.fromJson(component.toJson()), is(component)),
            () -> assertThat(texts(component.toLines().getFirst().getSegments()), contains("ABCD"))
        );
    }

    @Test
    @DisplayName("a section sign and the character after it restyle the rest of their text and are not drawn")
    void aCodeRestylesTheRestOfItsText() {
        List<TextSegment> split = runs("{\"text\": \"a§lb\"}");
        List<TextSegment> whole = runs("\"§lBold\"");
        List<TextSegment> siblings = runs("[\"\", {\"text\": \"§lA\"}, {\"text\": \"B\"}]");
        List<LineSegment> broken = TextSegment.literal("§la\nb").toLines();
        List<LineSegment> taken = TextSegment.literal("a§\nb").toLines();
        TextSegment component = TextSegment.fromJson("{\"text\": \"a§lb\"}");

        assertAll(
            () -> assertThat("the text either side of a code", texts(split), contains("a", "b")),
            () -> assertThat("only the text after it restyled",
                List.of(split.getFirst().isBold(), split.getLast().isBold()),
                contains(false, true)),
            () -> assertThat("a code opening the text", texts(whole), contains("Bold")),
            () -> assertThat("restyles all of it", whole.getFirst().isBold(), is(true)),
            () -> assertThat("a code in one component", texts(siblings), contains("A", "B")),
            () -> assertThat("restyles no other component's text",
                List.of(siblings.getFirst().isBold(), siblings.getLast().isBold()),
                contains(true, false)),
            () -> assertThat("a code before a line break in its text",
                broken.stream().map(line -> texts(line.getSegments())).toList(),
                contains(List.of("a"), List.of("b"))),
            () -> assertThat("restyles the text past the break",
                broken.stream().map(line -> line.getSegments().getFirst().isBold()).toList(),
                contains(true, true)),
            () -> assertThat("a sign directly before a line break takes it as its code", taken, hasSize(1)),
            () -> assertThat("so no line begins there", texts(taken.getFirst().getSegments()), contains("ab")),
            () -> assertThat("a component keeps its codes as written", component.getText(), is("a§lb")),
            () -> assertThat("and writes them back", component.toJson(), is(JsonParser.parseString("\"a§lb\"")))
        );
    }

    @Test
    @DisplayName("a code letter is read in its lower case, so a capital letter and the Kelvin sign are codes")
    void aCodeLetterIsReadInItsLowerCase() {
        List<TextSegment> bold = runs("\"§Lx\"");
        List<TextSegment> red = runs("\"§Cx\"");
        List<TextSegment> kelvin = runs("\"§\u212Ax\"");
        List<TextSegment> capitalK = runs("\"§Kx\"");
        List<TextSegment> reset = runs("\"§l§Rx\"");
        List<TextSegment> legacy = TextSegment.fromLegacy("§Lx", '§').getSegments();

        assertAll(
            () -> assertThat("a capital format code", texts(bold), contains("x")),
            () -> assertThat("turns its format on", bold.getFirst().isBold(), is(true)),
            () -> assertThat("a capital colour code", texts(red), contains("x")),
            () -> assertThat("sets its colour", red.getFirst().getColor(), is(Optional.of(ChatColor.Legacy.RED))),
            () -> assertThat("the Kelvin sign", texts(kelvin), contains("x")),
            () -> assertThat("is the obfuscated code", kelvin.getFirst().isObfuscated(), is(true)),
            () -> assertThat("a capital K", texts(capitalK), contains("x")),
            () -> assertThat("is the obfuscated code too", capitalK.getFirst().isObfuscated(), is(true)),
            () -> assertThat("a capital reset", texts(reset), contains("x")),
            () -> assertThat("returns to the text's own style", reset.getFirst().isBold(), is(false)),
            () -> assertThat("a capital in legacy text", texts(legacy), contains("x")),
            () -> assertThat("is its code too", legacy.getFirst().isBold(), is(true))
        );
    }

    @Test
    @DisplayName("a colour code sets its colour and turns all five formats off, the component's own included, "
        + "and keeps the rest of its style")
    void aColourCodeTurnsEveryFormatOff() {
        Optional<Boolean> off = Optional.of(false);
        TextSegment last = runs("\"§k§l§m§n§ox§cy\"").getLast();
        TextSegment own = runs("{\"text\": \"§cx\", \"bold\": true}").getLast();
        TextSegment styled = TextSegment.fromJson("{\"text\": \"§cx\", \"shadow_color\": -16777216, "
            + "\"insertion\": \"i\", \"font\": \"minecraft:alt\", "
            + "\"click_event\": {\"action\": \"run_command\", \"command\": \"/a\"}}");
        TextSegment kept = styled.toLines().getFirst().getSegments().getLast();
        List<TextSegment> graded = TextSegment.builder().text("§cx").gradient(GRADIENT).build()
            .toLines().getFirst().getSegments();
        List<TextSegment> cut = runs("[\"\", {\"text\": \"§c§nA\"}, "
            + "{\"text\": \"B\", \"color\": \"red\", \"underlined\": true}]");

        assertAll(
            () -> assertThat("the text after a colour code", last.getText(), is("y")),
            () -> assertThat("in its colour", last.getColor(), is(Optional.of(ChatColor.Legacy.RED))),
            () -> assertThat("with each format set false, not left unset",
                List.of(last.getBold(), last.getItalic(), last.getUnderlined(), last.getStrikethrough(),
                    last.getObfuscated()),
                contains(off, off, off, off, off)),
            () -> assertThat("a colour code over the component's own format",
                own.getColor(),
                is(Optional.of(ChatColor.Legacy.RED))),
            () -> assertThat("turns it off too", own.isBold(), is(false)),
            () -> assertThat("the rest of the style", kept.getText(), is("x")),
            () -> assertThat("the shadow colour kept",
                kept.getShadowColor().map(ChatColor::rgb),
                is(Optional.of(-16777216))),
            () -> assertThat("the insertion kept", kept.getInsertion(), is(Optional.of("i"))),
            () -> assertThat("the font kept", kept.getFont(), is(Optional.of(FontId.parse("minecraft:alt")))),
            () -> assertThat("the click event kept", kept.getClickEvent(), is(styled.getClickEvent())),
            () -> assertThat("a gradient", texts(graded), contains("x")),
            () -> assertThat("kept", graded.getFirst().getGradient(), is(Optional.of(GRADIENT))),
            () -> assertThat("a flag a code sets false is not one left unset", texts(cut), contains("A", "B"))
        );
    }

    @Test
    @DisplayName("a format code turns its one format on and keeps the colour and the formats already on")
    void aFormatCodeTurnsItsOneFormatOn() {
        Map<Character, Predicate<TextSegment>> formats = new LinkedHashMap<>();
        formats.put('k', TextSegment::isObfuscated);
        formats.put('l', TextSegment::isBold);
        formats.put('m', TextSegment::isStrikethrough);
        formats.put('n', TextSegment::isUnderlined);
        formats.put('o', TextSegment::isItalic);
        TextSegment both = runs("\"§l§ox\"").getFirst();

        assertAll(
            () -> assertAll(formats.entrySet().stream().map(format -> (Executable) () -> {
                TextSegment run = runs("\"§c§" + format.getKey() + "x\"").getFirst();

                assertAll(
                    () -> assertThat(format.getKey() + " after a colour", run.getText(), is("x")),
                    () -> assertThat(format.getKey() + " keeps it",
                        run.getColor(),
                        is(Optional.of(ChatColor.Legacy.RED))),
                    () -> assertThat(format.getKey() + " turns its format on", format.getValue().test(run), is(true))
                );
            })),
            () -> assertThat("a second format keeps the first",
                List.of(both.isBold(), both.isItalic()),
                contains(true, true))
        );
    }

    @Test
    @DisplayName("a reset returns to the style its text is shown in, as the client's font draws it, and to no style "
        + "in legacy text")
    void aResetReturnsToTheStyleItsTextIsShownIn() {
        List<TextSegment> own = runs("{\"text\": \"§c§oa§rb\", \"bold\": true, \"color\": \"gold\"}");
        TextSegment inherited = runs("{\"text\": \"\", \"bold\": true, \"extra\": [{\"text\": \"§oa§rb\"}]}").getLast();
        TextSegment legacy = TextSegment.fromLegacy("&cA&rB").getSegments().getLast();

        assertAll(
            () -> assertThat("the text either side of a reset", texts(own), contains("a", "b")),
            () -> assertThat("before it, the codes' colour",
                own.getFirst().getColor(),
                is(Optional.of(ChatColor.Legacy.RED))),
            () -> assertThat("and the codes' formats",
                List.of(own.getFirst().isItalic(), own.getFirst().isBold()),
                contains(true, false)),
            () -> assertThat("after it, the component's own style",
                own.getLast(),
                is(runs("{\"text\": \"b\", \"bold\": true, \"color\": \"gold\"}").getFirst())),
            () -> assertThat("a reset in an inherited style", inherited.getText(), is("b")),
            () -> assertThat("returns to that style",
                List.of(inherited.isBold(), inherited.isItalic()),
                contains(true, false)),
            () -> assertThat("a reset in legacy text", legacy.getText(), is("B")),
            () -> assertThat("returns to no colour, not white", legacy.getColor(), is(Optional.empty())),
            () -> assertThat("toLegacy writes the style a reset returns to",
                TextSegment.fromJson("{\"text\": \"a§rb\", \"bold\": true}").toLegacy(),
                is("§lab"))
        );
    }

    @Test
    @DisplayName("a sign and a character that is no code are dropped and leave the style as it was")
    void anUnknownCodeIsDropped() {
        List<TextSegment> red = runs("\"§cx§zy\"");
        List<TextSegment> twice = runs("\"a§§lb\"");
        List<TextSegment> graded = TextSegment.builder().text("ab§zcd").gradient(GRADIENT).build()
            .toLines().getFirst().getSegments();

        assertAll(
            () -> assertThat("a letter that is no code", texts(runs("\"a§zb\"")), contains("ab")),
            () -> assertThat("in a style", texts(red), contains("xy")),
            () -> assertThat("leaves that style", red.getFirst().getColor(), is(Optional.of(ChatColor.Legacy.RED))),
            () -> assertThat("a sign after a sign, which is no code", texts(twice), contains("alb")),
            () -> assertThat("so the letter after it is text", twice.getFirst().isBold(), is(false)),
            () -> assertThat("a space", texts(runs("\"a§ b\"")), contains("ab")),
            () -> assertThat("the high half of a surrogate pair, the low half left as text",
                texts(runs("\"a§\\uD83D\\uDE00b\"")),
                contains("a\uDE00b")),
            () -> assertThat("a dropped code between the two halves leaves them two runs",
                texts(runs("\"\\uD83D§z\\uDE00\"")),
                contains("\uD83D", "\uDE00")),
            () -> assertThat("a gradient restarts at a code that changes nothing", texts(graded), contains("ab", "cd")),
            () -> assertThat("each piece spreading it over itself",
                graded.stream().map(TextSegment::getGradient).toList(),
                contains(Optional.of(GRADIENT), Optional.of(GRADIENT))),
            () -> assertThat("in legacy text", texts(TextSegment.fromLegacy("a§zb", '§').getSegments()), contains("ab"))
        );
    }

    @Test
    @DisplayName("a sign ending its text is dropped, and the next component opens on its own style")
    void aSignEndingItsTextIsDropped() {
        List<TextSegment> before = runs("[\"\", {\"text\": \"a§\"}, {\"text\": \"lb\"}]");

        assertAll(
            () -> assertThat("in a string", texts(runs("\"ab§\"")), contains("ab")),
            () -> assertThat("before the next component", texts(before), contains("alb")),
            () -> assertThat("which opens on its own style", before.getFirst().isBold(), is(false)),
            () -> assertThat("in legacy text", texts(TextSegment.fromLegacy("x§", '§').getSegments()), contains("x"))
        );
    }

    @Test
    @DisplayName("the runs toLines and fromLegacy answer read the codes of each text they come from - a string, "
        + "a component's text, a sibling, a translation's own text, a sprite's fallback, a player sprite's "
        + "name, a keybind's name and legacy text")
    void eachSourceOfARunsTextReadsItsCodes() {
        Map<String, List<TextSegment>> bold = new LinkedHashMap<>();
        bold.put("a string", runs("\"§lx\""));
        bold.put("a component's text", runs("{\"text\": \"§lx\"}"));
        bold.put("a sibling", runs("{\"text\": \"\", \"extra\": [\"§lx\"]}"));
        bold.put("a literal", TextSegment.literal("§lx").toLines().getFirst().getSegments());
        bold.put("a sprite's fallback",
            runs("[\"\", {\"type\": \"object\", \"sprite\": \"item/apple\", \"fallback\": \"§lx\"}]"));
        bold.put("legacy text", TextSegment.fromLegacy("§lx", '&').getSegments());
        List<TextSegment> translated = runs("{\"translate\": \"k\", \"fallback\": \"§l%s §oy\", \"with\": [\"x\"]}");
        List<TextSegment> keybind = runs("{\"keybind\": \"§lkey.jump\"}");
        List<TextSegment> head = runs("[\"\", {\"type\": \"object\", \"player\": \"§lX\"}]");

        assertAll(
            () -> assertAll(bold.entrySet().stream().map(source -> (Executable) () -> assertAll(
                () -> assertThat(source.getKey(), texts(source.getValue()), contains("x")),
                () -> assertThat(source.getKey() + ", restyled", source.getValue().getFirst().isBold(), is(true))
            ))),
            () -> assertThat("a translation's own text", texts(translated), contains("x ", "y")),
            () -> assertThat("a code restyling only its own piece of it",
                List.of(translated.getFirst().isBold(), translated.getLast().isItalic()),
                contains(false, true)),
            () -> assertThat("a player sprite's name, in the stand-in drawn for it",
                texts(head),
                contains("[", "X head]")),
            () -> assertThat("restyling the stand-in from the name on",
                List.of(head.getFirst().isBold(), head.getLast().isBold()),
                contains(false, true)),
            () -> assertThat("a keybind's name", texts(keybind), contains("key.jump")),
            () -> assertThat("read as any text", keybind.getFirst().isBold(), is(true))
        );
    }

    @Test
    @DisplayName("a substitute stands for the sign only before a code character written as the code is, and is "
        + "text anywhere else")
    void aSubstituteStandsForTheSignOnlyBeforeACode() {
        List<TextSegment> prose = TextSegment.fromLegacy("Q&A, R&D &lbold&").getSegments();
        List<TextSegment> capital = TextSegment.fromLegacy("&Lx").getSegments();
        List<TextSegment> sign = TextSegment.fromLegacy("§Lx").getSegments();

        assertAll(
            () -> assertThat("text before a letter that is no code and at the end",
                texts(prose),
                contains("Q&A, R&D ", "bold&")),
            () -> assertThat("a sign before a code",
                List.of(prose.getFirst().isBold(), prose.getLast().isBold()),
                contains(false, true)),
            () -> assertThat("text before a capital", texts(capital), contains("&Lx")),
            () -> assertThat("which turns nothing on", capital.getFirst().isBold(), is(false)),
            () -> assertThat("text before a character that is no code",
                texts(TextSegment.fromLegacy("&zx").getSegments()),
                contains("&zx")),
            () -> assertThat("the section sign itself, read in its lower case", texts(sign), contains("x")),
            () -> assertThat("so its capital is a code", sign.getFirst().isBold(), is(true))
        );
    }

    @Test
    @DisplayName("each piece of legacy text between two codes takes the apostrophe substitution a builder's text "
        + "applies")
    void eachLegacyPieceTakesTheApostropheSubstitution() {
        List<TextSegment> pieces = TextSegment.fromLegacy("it's &lBob's").getSegments();

        assertAll(
            () -> assertThat("an apostrophe on either side of a code",
                texts(pieces),
                contains("it\u2019s ", "Bob\u2019s")),
            () -> assertThat("in the style each piece is read in",
                List.of(pieces.getFirst().isBold(), pieces.getLast().isBold()),
                contains(false, true)),
            () -> assertThat("an escaped apostrophe",
                texts(TextSegment.fromLegacy("\\'x\\'").getSegments()),
                contains("'x'"))
        );
    }

    /**
     * Reads a JSON text component holding one line and answers that line's runs.
     *
     * @param json the component's JSON
     * @return the runs of its one line
     */
    private static List<TextSegment> runs(String json) {
        List<LineSegment> lines = TextSegment.fromJson(json).toLines();
        assertThat(lines, hasSize(1));
        return lines.getFirst().getSegments();
    }

    /**
     * Answers the runs of the one line two segments make as the siblings of an unstyled component.
     *
     * @param first the first sibling
     * @param second the second sibling
     * @return the runs of their line
     */
    private static List<TextSegment> runs(TextSegment first, TextSegment second) {
        return TextSegment.builder().extra(List.of(first, second)).build().toLines().getFirst().getSegments();
    }

    /**
     * Writes a sibling component showing {@code text}, with one style member where one is given.
     *
     * @param text the text
     * @param member the style member's JSON, or empty for none
     * @return the sibling's JSON
     */
    private static String sibling(String text, String member) {
        return member.isEmpty() ? "{\"text\": \"" + text + "\"}" : "{\"text\": \"" + text + "\", " + member + "}";
    }

    /**
     * Answers each run's text, in order.
     *
     * @param runs the runs
     * @return their texts
     */
    private static List<String> texts(List<TextSegment> runs) {
        return runs.stream().map(TextSegment::getText).toList();
    }

    /**
     * Answers what each run draws - its text, its colour's value and whether each of the five formats is
     * on.
     *
     * @param runs the runs
     * @return what each draws, in order
     */
    private static List<List<Object>> drawn(List<TextSegment> runs) {
        return runs.stream()
            .map(run -> List.<Object>of(
                run.getText(),
                run.getColor().map(color -> color.rgb() & 0xFFFFFF),
                run.isBold(),
                run.isItalic(),
                run.isUnderlined(),
                run.isStrikethrough(),
                run.isObfuscated()
            ))
            .toList();
    }

}
