package lib.minecraft.text.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.text.ChatFormat;
import lib.minecraft.text.TextJson;
import lib.minecraft.text.exception.TextJsonException;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What clicking a text component does - the {@code click_event} of a 26.1 component, written as an
 * {@code action} and the payload that action takes.
 *
 * <p>Vanilla's JSON refuses {@code open_file}, which only the client itself creates, so it has no form
 * here.</p>
 */
public sealed interface ClickEvent permits ClickEvent.OpenUrl, ClickEvent.RunCommand, ClickEvent.SuggestCommand, ClickEvent.ShowDialog, ClickEvent.ChangePage, ClickEvent.CopyToClipboard, ClickEvent.Custom {

    /**
     * The action this event performs.
     *
     * @return the action
     */
    @NotNull Action action();

    /**
     * Writes the payload of this event into its JSON object.
     *
     * @param object the event's object
     */
    void write(@NotNull JsonObject object);

    /**
     * Writes this event as vanilla writes a {@code click_event}.
     *
     * @return the event's object
     */
    default @NotNull JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("action", this.action().getSerializedName());
        this.write(object);
        return object;
    }

    /**
     * Reads a {@code click_event} as vanilla reads one.
     *
     * @param json the event's JSON
     * @return the event
     * @throws TextJsonException if the JSON is no object, names no action vanilla's JSON accepts, or
     *     holds a payload the action does not read
     */
    static @NotNull ClickEvent fromJson(@NotNull JsonElement json) {
        JsonObject object = TextJson.object(json, "click_event");
        String name = TextJson.string(TextJson.required(object, "action"), "action");

        if (name.equals("open_file"))
            throw new TextJsonException("Click event type not allowed: '%s'", name);

        Action action = Action.findBySerializedName(name).orElseThrow(() -> new TextJsonException("Unknown click event action '%s'", name));

        return switch (action) {
            case OPEN_URL -> OpenUrl.read(object);
            case RUN_COMMAND -> new RunCommand(chatString(TextJson.required(object, "command"), "command"));
            case SUGGEST_COMMAND -> new SuggestCommand(chatString(TextJson.required(object, "command"), "command"));
            case SHOW_DIALOG -> new ShowDialog(TextJson.required(object, "dialog"));
            case CHANGE_PAGE -> new ChangePage(TextJson.integer(TextJson.required(object, "page"), "page"));
            case COPY_TO_CLIPBOARD -> new CopyToClipboard(TextJson.string(TextJson.required(object, "value"), "value"));
            case CUSTOM -> new Custom(TextJson.identifier(TextJson.required(object, "id"), "id"), TextJson.member(object, "payload"));
        };
    }

    /**
     * Reads a string that may hold only characters vanilla allows in chat - none below a space, no
     * {@code DEL} and no section sign.
     */
    private static @NotNull String chatString(@NotNull JsonElement element, @NotNull String what) {
        String value = TextJson.string(element, what);

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == ChatFormat.SECTION_SYMBOL || c < ' ' || c == 0x7F)
                throw new TextJsonException("Disallowed chat character in '%s': '%s'", what, value);
        }

        return value;
    }

    /**
     * Opens a web page - {@code {"action": "open_url", "url": "https://..."}}.
     *
     * @param url the page, an {@code http} or {@code https} URI
     */
    record OpenUrl(@NotNull URI url) implements ClickEvent {

        /**
         * The schemes vanilla opens.
         */
        private static final @NotNull Set<String> SCHEMES = Set.of("http", "https");

        public OpenUrl {
            if (url.getScheme() == null || !SCHEMES.contains(url.getScheme().toLowerCase(Locale.ROOT)))
                throw new TextJsonException("Unsupported protocol in '%s'", url);
        }

        static @NotNull OpenUrl read(@NotNull JsonObject object) {
            String url = TextJson.string(TextJson.required(object, "url"), "url");

            try {
                return new OpenUrl(new URI(url));
            } catch (URISyntaxException exception) {
                throw new TextJsonException(exception, "Invalid URL '%s'", url);
            }
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.OPEN_URL;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("url", this.url.toString());
        }

    }

    /**
     * Runs a command - {@code {"action": "run_command", "command": "..."}}.
     *
     * @param command the command
     */
    record RunCommand(@NotNull String command) implements ClickEvent {

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.RUN_COMMAND;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("command", this.command);
        }

    }

    /**
     * Puts a command in the chat box - {@code {"action": "suggest_command", "command": "..."}}.
     *
     * @param command the command
     */
    record SuggestCommand(@NotNull String command) implements ClickEvent {

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.SUGGEST_COMMAND;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("command", this.command);
        }

    }

    /**
     * Shows a dialog - {@code {"action": "show_dialog", "dialog": ...}}, the dialog's identifier or the
     * dialog itself written inline.
     *
     * @param dialog the dialog's identifier as a JSON string, or the dialog as a JSON object
     */
    record ShowDialog(@NotNull JsonElement dialog) implements ClickEvent {

        public ShowDialog {
            if (dialog instanceof JsonPrimitive primitive && primitive.isString())
                dialog = new JsonPrimitive(TextJson.identifier(primitive.getAsString(), "dialog"));
            else if (dialog.isJsonObject())
                dialog = dialog.deepCopy();
            else
                throw new TextJsonException("Expected 'dialog' to be an identifier or a dialog, found '%s'", dialog);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.SHOW_DIALOG;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.add("dialog", this.dialog.deepCopy());
        }

    }

    /**
     * Turns a book to a page - {@code {"action": "change_page", "page": 2}}.
     *
     * @param page the page, from {@code 1}
     */
    record ChangePage(int page) implements ClickEvent {

        public ChangePage {
            if (page < 1)
                throw new TextJsonException("Expected 'page' to be positive, found '%s'", page);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.CHANGE_PAGE;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("page", this.page);
        }

    }

    /**
     * Copies text to the clipboard - {@code {"action": "copy_to_clipboard", "value": "..."}}.
     *
     * @param value the text copied
     */
    record CopyToClipboard(@NotNull String value) implements ClickEvent {

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.COPY_TO_CLIPBOARD;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("value", this.value);
        }

    }

    /**
     * Sends a custom action to the server - {@code {"action": "custom", "id": "...", "payload": ...}}.
     *
     * @param id the action's identifier
     * @param payload the value sent with it, which vanilla reads as NBT and which is kept here as the JSON
     *     it was written as
     */
    record Custom(@NotNull String id, @NotNull Optional<JsonElement> payload) implements ClickEvent {

        public Custom {
            payload = payload.map(JsonElement::deepCopy);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.CUSTOM;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("id", this.id);
            this.payload.ifPresent(payload -> object.add("payload", payload.deepCopy()));
        }

    }

    /**
     * The actions a {@code click_event} takes, under the names vanilla's JSON gives them.
     */
    @Getter
    @EnumLookup
    @RequiredArgsConstructor
    enum Action {

        OPEN_URL("open_url"),
        RUN_COMMAND("run_command"),
        SUGGEST_COMMAND("suggest_command"),
        SHOW_DIALOG("show_dialog"),
        CHANGE_PAGE("change_page"),
        COPY_TO_CLIPBOARD("copy_to_clipboard"),
        CUSTOM("custom");

        /**
         * The name the action is written under, which a lookup matches exactly.
         */
        @KeyField
        private final @NotNull String serializedName;

        @Override
        public @NotNull String toString() {
            return this.serializedName;
        }

    }

}
