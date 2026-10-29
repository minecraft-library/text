package lib.minecraft.renderer.text;

import com.google.gson.JsonParser;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

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
 * legacy format code adds to the style already on.
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
            () -> assertThat("toLegacy's own text reads back", TextSegment.fromLegacy(written.toLegacy()).getSegments(), contains(written.toLines().getFirst().getSegments().toArray(TextSegment[]::new)))
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

}
