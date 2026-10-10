package lib.minecraft.text.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.text.TextJson;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.exception.TextJsonException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * What hovering over a text component shows - the {@code hover_event} of a 26.1 component, written as an
 * {@code action} and the payload that action takes.
 */
public sealed interface HoverEvent permits HoverEvent.ShowText, HoverEvent.ShowItem, HoverEvent.ShowEntity {

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
     * Writes this event as vanilla writes a {@code hover_event}.
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
     * Reads a {@code hover_event} as vanilla reads one.
     *
     * @param json the event's JSON
     * @return the event
     * @throws TextJsonException if the JSON is no object, names an unknown action, or holds a payload the
     *     action does not read
     */
    static @NotNull HoverEvent fromJson(@NotNull JsonElement json) {
        JsonObject object = TextJson.object(json, "hover_event");
        String name = TextJson.string(TextJson.required(object, "action"), "action");
        Action action = Action.findBySerializedName(name).orElseThrow(() -> new TextJsonException("Unknown hover event action '%s'", name));

        return switch (action) {
            case SHOW_TEXT -> new ShowText(TextSegment.fromJson(TextJson.required(object, "value")));
            case SHOW_ITEM -> ShowItem.read(object);
            case SHOW_ENTITY -> ShowEntity.read(object);
        };
    }

    /**
     * Shows a component - {@code {"action": "show_text", "value": ...}}.
     *
     * @param value the component shown
     */
    record ShowText(@NotNull TextSegment value) implements HoverEvent {

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.SHOW_TEXT;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.add("value", this.value.toJson());
        }

    }

    /**
     * Shows an item's tooltip - {@code {"action": "show_item", "id": "...", "count": 1, "components":
     * {...}}}.
     *
     * @param id the item's identifier
     * @param count the stack size, from {@code 1} to {@code 99}, written only where it is not {@code 1}
     * @param components the stack's component patch, kept as the JSON it was written as and written only
     *     where it is not empty
     */
    record ShowItem(@NotNull String id, int count, @NotNull JsonObject components) implements HoverEvent {

        public ShowItem {
            if (count < 1 || count > 99)
                throw new TextJsonException("Expected 'count' to be from 1 to 99, found '%s'", count);

            components = components.deepCopy();
        }

        static @NotNull ShowItem read(@NotNull JsonObject object) {
            return new ShowItem(
                TextJson.identifier(TextJson.required(object, "id"), "id"),
                TextJson.member(object, "count").map(count -> TextJson.integer(count, "count")).orElse(1),
                TextJson.member(object, "components").map(components -> TextJson.object(components, "components")).orElseGet(JsonObject::new)
            );
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.SHOW_ITEM;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("id", this.id);

            if (this.count != 1)
                object.addProperty("count", this.count);

            if (!this.components.isEmpty())
                object.add("components", this.components.deepCopy());
        }

    }

    /**
     * Shows an entity's tooltip - {@code {"action": "show_entity", "id": "...", "uuid": ..., "name":
     * ...}}.
     *
     * @param id the entity type's identifier
     * @param uuid the entity's UUID, read from a list of four ints or a string and written as the list
     * @param name the entity's name
     */
    record ShowEntity(@NotNull String id, @NotNull UUID uuid, @NotNull Optional<TextSegment> name) implements HoverEvent {

        static @NotNull ShowEntity read(@NotNull JsonObject object) {
            return new ShowEntity(
                TextJson.identifier(TextJson.required(object, "id"), "id"),
                uuid(TextJson.required(object, "uuid")),
                TextJson.member(object, "name").map(TextSegment::fromJson)
            );
        }

        /**
         * Reads a UUID as vanilla's lenient reader does - four ints, most significant first, or the
         * string form.
         */
        private static @NotNull UUID uuid(@NotNull JsonElement element) {
            if (element.isJsonArray()) {
                JsonArray ints = element.getAsJsonArray();

                if (ints.size() != 4)
                    throw new TextJsonException("Expected 'uuid' to be a list of 4 ints, found '%s'", element);

                int[] parts = new int[4];

                for (int i = 0; i < 4; i++)
                    parts[i] = TextJson.integer(ints.get(i), "uuid");

                return new UUID((long) parts[0] << 32 | parts[1] & 0xFFFFFFFFL, (long) parts[2] << 32 | parts[3] & 0xFFFFFFFFL);
            }

            String value = TextJson.string(element, "uuid");

            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new TextJsonException(exception, "Invalid UUID '%s'", value);
            }
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Action action() {
            return Action.SHOW_ENTITY;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("id", this.id);

            JsonArray uuid = new JsonArray(4);
            uuid.add((int) (this.uuid.getMostSignificantBits() >> 32));
            uuid.add((int) this.uuid.getMostSignificantBits());
            uuid.add((int) (this.uuid.getLeastSignificantBits() >> 32));
            uuid.add((int) this.uuid.getLeastSignificantBits());
            object.add("uuid", uuid);

            this.name.ifPresent(name -> object.add("name", name.toJson()));
        }

    }

    /**
     * The actions a {@code hover_event} takes, under the names vanilla's JSON gives them.
     */
    @Getter
    @EnumLookup
    @RequiredArgsConstructor
    enum Action {

        SHOW_TEXT("show_text"),
        SHOW_ITEM("show_item"),
        SHOW_ENTITY("show_entity");

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
