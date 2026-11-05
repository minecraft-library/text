package lib.minecraft.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Collector;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.ToString;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.StreamUtil;
import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;

/**
 * One line of text - the runs drawn left to right, each plain text in the style it is shown in.
 */
@Getter
@ToString
@ClassBuilder
@EqualsAndHashCode
public final class LineSegment {

    /**
     * The runs, in drawing order.
     */
    @Collector(singular = true)
    private final @NotNull ConcurrentList<TextSegment> segments = Concurrent.newList();

    /**
     * Explode the {@link #getSegments()} into single-words for use in a dynamic newline system.
     */
    public @NotNull ConcurrentList<TextSegment> explode() {
        return this.getSegments()
            .stream()
            .flatMap(segment -> segment.explode().stream())
            .collect(Concurrent.toList());
    }

    public int length() {
        return this.getSegments()
            .stream()
            .mapToInt(segment -> StringUtil.length(segment.getText()))
            .sum();
    }

    /**
     * Writes this line as a text component list - an empty first element the runs are appended to, so no
     * run inherits another's style.
     *
     * @return the list
     */
    public @NotNull JsonElement toJson() {
        JsonArray rootArray = new JsonArray();
        rootArray.add("");
        this.getSegments().forEach(segment -> rootArray.add(segment.toJson()));
        return rootArray;
    }

    /**
     * Reads legacy text into lines of runs, split at each line break in it - {@code \r\n}, {@code \n}, or a
     * backslash written before an {@code n} - each line read as {@link TextSegment#fromLegacy(String, char)}
     * reads one, so a code's style ends at its line and a section sign ending a line is dropped.
     *
     * <p>{@code symbolSubstitute} stands for the section sign ({@link ChatFormat#SECTION_SYMBOL}) only before a
     * code character written as the code is, and is text anywhere else. Neighbours in one style are one run,
     * and an empty line or a line of codes alone is a line of no runs.</p>
     *
     * @param legacyText the legacy text
     * @param symbolSubstitute the character that stands for the section sign before a code
     * @return the lines, one more than the line breaks in the text
     */
    public static @NotNull ConcurrentList<LineSegment> fromLegacy(@NotNull String legacyText, char symbolSubstitute) {
        return StreamUtil.ofArrays(legacyText.split("(\r?\n|\\\\n)", -1))
            .map(line -> TextSegment.fromLegacy(line, symbolSubstitute))
            .collect(Concurrent.toList());
    }

}
