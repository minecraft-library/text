package lib.minecraft.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lib.minecraft.text.exception.TextJsonException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a {@link TextSegment} shows before its style and its siblings - one of the seven kinds of contents
 * a 26.1 text component carries.
 *
 * <p>A component's JSON names its kind with a {@code type} member when it has one, and otherwise by the
 * first kind, in vanilla's order, whose members it holds: {@link Text text}, {@link Translatable
 * translatable}, {@link Keybind keybind}, {@link Score score}, {@link Selector selector}, {@link Nbt nbt}
 * and {@link Sprite object}. Written back, a component never names its kind, as vanilla's never does.</p>
 *
 * <p>Nothing here resolves a component against a game: a score, an entity selector or an NBT path reads
 * and writes as the text it is, and {@link #plainText()} is what the contents show unresolved - which is
 * what vanilla shows for them before a server resolves them.</p>
 */
sealed interface TextContents permits TextContents.Text, TextContents.Translatable, TextContents.Keybind, TextContents.Score, TextContents.Selector, TextContents.Nbt, TextContents.Sprite {

    /**
     * The {@code type} name vanilla gives this kind of contents.
     *
     * @return the type name
     */
    @NotNull String type();

    /**
     * Returns the text these contents show unresolved.
     *
     * @return the plain text
     */
    @NotNull String plainText();

    /**
     * Writes the members of these contents into a component's JSON object.
     *
     * @param object the component's object
     */
    void write(@NotNull JsonObject object);

    /**
     * Reads the contents of a component's JSON object.
     *
     * @param object the component's object
     * @return the contents
     * @throws TextJsonException if the object names an unknown kind, or holds the members of no kind
     */
    static @NotNull TextContents read(@NotNull JsonObject object) {
        Optional<JsonElement> type = TextJson.member(object, "type");

        if (type.isPresent()) {
            String name = TextJson.string(type.get(), "type");

            return switch (name) {
                case "text" -> Text.read(object);
                case "translatable" -> Translatable.read(object);
                case "keybind" -> Keybind.read(object);
                case "score" -> Score.read(object);
                case "selector" -> Selector.read(object);
                case "nbt" -> Nbt.read(object);
                case "object" -> Sprite.read(object);
                default -> throw new TextJsonException("Unknown text component type '%s'", name);
            };
        }

        return firstReading(object, "text component contents", List.of(Text::read, Translatable::read, Keybind::read, Score::read, Selector::read, Nbt::read, Sprite::read));
    }

    /**
     * Returns what the first reader that accepts an object reads, as vanilla tries its untyped forms in
     * order.
     *
     * @param object the object
     * @param what the name of what is read, for the message
     * @param readers the readers in order
     * @param <T> the type read
     * @return the first reading
     * @throws TextJsonException if no reader accepts the object
     */
    private static <T> @NotNull T firstReading(@NotNull JsonObject object, @NotNull String what, @NotNull List<Function<JsonObject, ? extends T>> readers) {
        for (Function<JsonObject, ? extends T> reader : readers) {
            try {
                return reader.apply(object);
            } catch (TextJsonException ignored) {
                // Vanilla moves on to the next form when one does not read.
            }
        }

        throw new TextJsonException("No %s in '%s'", what, object);
    }

    /**
     * Reads an optional member as a component, absent where the member is.
     */
    private static @NotNull Optional<TextSegment> segment(@NotNull JsonObject object, @NotNull String key) {
        return TextJson.member(object, key).map(TextSegment::fromJson);
    }

    /**
     * Reads an optional member leniently, as vanilla's lenient optional members read: a member that does
     * not read is taken as absent rather than refused.
     */
    private static <T> @NotNull Optional<T> lenient(@NotNull JsonObject object, @NotNull String key, @NotNull Function<JsonElement, T> reader) {
        try {
            return TextJson.member(object, key).map(reader);
        } catch (TextJsonException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Literal text - {@code {"text": "..."}}.
     *
     * @param text the text
     */
    record Text(@NotNull String text) implements TextContents {

        /**
         * The empty text.
         */
        public static final @NotNull Text EMPTY = new Text("");

        static @NotNull Text read(@NotNull JsonObject object) {
            return new Text(TextJson.string(TextJson.required(object, "text"), "text"));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "text";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return this.text;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("text", this.text);
        }

    }

    /**
     * A translation key, its fallback and its arguments - {@code {"translate": "...", "fallback": "...",
     * "with": [...]}}.
     *
     * <p>No translation table is read here, so the text shown unresolved is the fallback, or the key where
     * there is none, with each {@code %s} and {@code %1$s} placeholder replaced by its argument as vanilla
     * replaces it, and the whole format shown as it is where a placeholder does not resolve.</p>
     *
     * @param key the translation key
     * @param fallback the text shown where no translation exists
     * @param arguments the arguments the placeholders take, an empty list writing no {@code with}
     */
    record Translatable(@NotNull String key, @NotNull Optional<String> fallback, @NotNull List<Argument> arguments) implements TextContents {

        /**
         * The placeholders of a translation format, as vanilla matches them.
         */
        private static final @NotNull Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

        public Translatable {
            arguments = List.copyOf(arguments);
        }

        static @NotNull Translatable read(@NotNull JsonObject object) {
            String key = TextJson.string(TextJson.required(object, "translate"), "translate");
            Optional<String> fallback = lenient(object, "fallback", element -> TextJson.string(element, "fallback"));
            List<Argument> arguments = new ArrayList<>();

            TextJson.member(object, "with").ifPresent(with -> {
                if (!with.isJsonArray())
                    throw new TextJsonException("Expected 'with' to be a list, found '%s'", with);

                for (JsonElement element : with.getAsJsonArray())
                    arguments.add(Argument.read(element));
            });

            return new Translatable(key, fallback, arguments);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "translatable";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            StringBuilder builder = new StringBuilder();

            for (TextSegment part : this.decompose())
                builder.append(part.toPlainText());

            return builder.toString();
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("translate", this.key);
            this.fallback.ifPresent(fallback -> object.addProperty("fallback", fallback));

            if (!this.arguments.isEmpty()) {
                JsonArray with = new JsonArray();
                this.arguments.forEach(argument -> with.add(argument.toJson()));
                object.add("with", with);
            }
        }

        /**
         * Splits the format this translation shows unresolved into its literal pieces and its arguments,
         * in order - the format as one literal piece where a placeholder does not resolve.
         *
         * @return the pieces, each literal one an unstyled text segment
         */
        public @NotNull List<TextSegment> decompose() {
            String format = this.fallback.orElse(this.key);

            try {
                List<TextSegment> parts = new ArrayList<>();
                Matcher matcher = FORMAT.matcher(format);
                int next = 0;
                int start = 0;

                while (matcher.find(start)) {
                    if (matcher.start() > start)
                        parts.add(literal(format.substring(start, matcher.start())));

                    String kind = matcher.group(2);
                    String placeholder = format.substring(matcher.start(), matcher.end());

                    if (kind.equals("%") && placeholder.equals("%%"))
                        parts.add(TextSegment.literal("%"));
                    else if (kind.equals("s")) {
                        String position = matcher.group(1);
                        parts.add(this.argument(position != null ? Integer.parseInt(position) - 1 : next++));
                    } else
                        throw new IllegalArgumentException("Unsupported format '" + placeholder + "'");

                    start = matcher.end();
                }

                if (start < format.length())
                    parts.add(literal(format.substring(start)));

                return parts;
            } catch (IllegalArgumentException unresolved) {
                return List.of(TextSegment.literal(format));
            }
        }

        /**
         * Returns one literal piece of a format, which may hold no stray {@code %}.
         */
        private static @NotNull TextSegment literal(@NotNull String piece) {
            if (piece.indexOf('%') != -1)
                throw new IllegalArgumentException("Stray '%' in '" + piece + "'");

            return TextSegment.literal(piece);
        }

        /**
         * Returns the argument at an index as the segment it shows.
         */
        private @NotNull TextSegment argument(int index) {
            if (index < 0 || index >= this.arguments.size())
                throw new IllegalArgumentException("No argument at index " + index);

            return switch (this.arguments.get(index)) {
                case Argument.Value value -> TextSegment.literal(String.valueOf(value.value()));
                case Argument.Segment segment -> segment.segment();
            };
        }

        /**
         * One argument of a translation - a plain value, or a component.
         */
        public sealed interface Argument permits Argument.Value, Argument.Segment {

            /**
             * Reads one element of {@code with} as vanilla does: a string, number or boolean as the plain
             * value it is, a number narrowed as vanilla narrows one, and anything else as a component,
             * which becomes the plain string it holds where it carries no style and no siblings.
             *
             * @param element the element
             * @return the argument
             * @throws TextJsonException if the element is {@code null} or no component
             */
            static @NotNull Argument read(@NotNull JsonElement element) {
                if (element instanceof JsonPrimitive primitive) {
                    if (primitive.isString()) return new Value(primitive.getAsString());
                    if (primitive.isBoolean()) return new Value(primitive.getAsBoolean());
                    return new Value(TextJson.narrowest(primitive));
                }

                if (element.isJsonNull())
                    throw new TextJsonException("A translation argument may not be null");

                TextSegment segment = TextSegment.fromJson(element);
                return segment.collapsesToString() ? new Value(segment.getText()) : new Segment(segment);
            }

            /**
             * Writes this argument as an element of {@code with}.
             *
             * @return the element
             */
            @NotNull JsonElement toJson();

            /**
             * A plain value - a {@link String}, a {@link Number} or a {@link Boolean}.
             *
             * @param value the value
             */
            record Value(@NotNull Object value) implements Argument {

                public Value {
                    if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
                        throw new IllegalArgumentException("A plain argument is a string, number or boolean, not " + value.getClass().getName());
                }

                /** {@inheritDoc} */
                @Override
                public @NotNull JsonElement toJson() {
                    return switch (this.value) {
                        case String string -> new JsonPrimitive(string);
                        case Number number -> new JsonPrimitive(number);
                        case Boolean bool -> new JsonPrimitive(bool);
                        default -> throw new IllegalStateException("Unexpected argument " + this.value);
                    };
                }

            }

            /**
             * A component.
             *
             * @param segment the component
             */
            record Segment(@NotNull TextSegment segment) implements Argument {

                /** {@inheritDoc} */
                @Override
                public @NotNull JsonElement toJson() {
                    return this.segment.toJson();
                }

            }

        }

    }

    /**
     * A key binding, shown as the key it is bound to - {@code {"keybind": "key.jump"}}. Unresolved it shows
     * its name, as vanilla shows a binding it cannot resolve.
     *
     * @param keybind the key binding's name
     */
    record Keybind(@NotNull String keybind) implements TextContents {

        static @NotNull Keybind read(@NotNull JsonObject object) {
            return new Keybind(TextJson.string(TextJson.required(object, "keybind"), "keybind"));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "keybind";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return this.keybind;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("keybind", this.keybind);
        }

    }

    /**
     * A scoreboard score - {@code {"score": {"name": "...", "objective": "..."}}}. Unresolved it shows
     * nothing.
     *
     * @param name the score holder - a player name or an entity selector
     * @param objective the objective
     */
    record Score(@NotNull String name, @NotNull String objective) implements TextContents {

        static @NotNull Score read(@NotNull JsonObject object) {
            JsonObject score = TextJson.object(TextJson.required(object, "score"), "score");
            return new Score(
                TextJson.string(TextJson.required(score, "name"), "name"),
                TextJson.string(TextJson.required(score, "objective"), "objective")
            );
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "score";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return "";
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            JsonObject score = new JsonObject();
            score.addProperty("name", this.name);
            score.addProperty("objective", this.objective);
            object.add("score", score);
        }

    }

    /**
     * The names of the entities an entity selector matches - {@code {"selector": "@p", "separator":
     * ...}}. Unresolved it shows the selector, as vanilla does.
     *
     * @param selector the entity selector
     * @param separator the component shown between two names
     */
    record Selector(@NotNull String selector, @NotNull Optional<TextSegment> separator) implements TextContents {

        static @NotNull Selector read(@NotNull JsonObject object) {
            return new Selector(TextJson.string(TextJson.required(object, "selector"), "selector"), segment(object, "separator"));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "selector";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return this.selector;
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("selector", this.selector);
            this.separator.ifPresent(separator -> object.add("separator", separator.toJson()));
        }

    }

    /**
     * Values read off an NBT path - {@code {"nbt": "...", "interpret": false, "plain": false, "separator":
     * ..., "entity" | "block" | "storage": "..."}}. Unresolved it shows nothing.
     *
     * <p>{@code interpret}, {@code plain} and {@code separator} read leniently, as vanilla reads them: one
     * that does not read is taken as absent rather than refused.</p>
     *
     * @param path the NBT path
     * @param interpret whether each value read is parsed as a component
     * @param plain whether each value is shown without the colours vanilla gives NBT
     * @param separator the component shown between two values
     * @param source where the values are read from
     */
    record Nbt(@NotNull String path, boolean interpret, boolean plain, @NotNull Optional<TextSegment> separator, @NotNull DataSource source) implements TextContents {

        public Nbt {
            if (interpret && plain)
                throw new TextJsonException("'interpret' and 'plain' cannot both be set");
        }

        static @NotNull Nbt read(@NotNull JsonObject object) {
            return new Nbt(
                TextJson.string(TextJson.required(object, "nbt"), "nbt"),
                lenient(object, "interpret", element -> TextJson.bool(element, "interpret")).orElse(false),
                lenient(object, "plain", element -> TextJson.bool(element, "plain")).orElse(false),
                lenient(object, "separator", TextSegment::fromJson),
                DataSource.read(object)
            );
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "nbt";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return "";
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            object.addProperty("nbt", this.path);

            if (this.interpret)
                object.addProperty("interpret", true);

            if (this.plain)
                object.addProperty("plain", true);

            this.separator.ifPresent(separator -> object.add("separator", separator.toJson()));
            this.source.write(object);
        }

        /**
         * Where an NBT path reads from - named by a {@code source} member when there is one, and otherwise
         * by the first of {@code entity}, {@code block} and {@code storage} present.
         */
        public sealed interface DataSource permits DataSource.Entity, DataSource.Block, DataSource.Storage {

            /**
             * Reads the source of an NBT component's object.
             *
             * @param object the component's object
             * @return the source
             * @throws TextJsonException if the object names an unknown source, or holds none
             */
            static @NotNull DataSource read(@NotNull JsonObject object) {
                Optional<JsonElement> source = TextJson.member(object, "source");

                if (source.isPresent()) {
                    String name = TextJson.string(source.get(), "source");

                    return switch (name) {
                        case "entity" -> Entity.read(object);
                        case "block" -> Block.read(object);
                        case "storage" -> Storage.read(object);
                        default -> throw new TextJsonException("Unknown NBT source '%s'", name);
                    };
                }

                return firstReading(object, "NBT source", List.of(Entity::read, Block::read, Storage::read));
            }

            /**
             * Writes this source into the component's object.
             *
             * @param object the component's object
             */
            void write(@NotNull JsonObject object);

            /**
             * The entities an entity selector matches.
             *
             * @param selector the entity selector
             */
            record Entity(@NotNull String selector) implements DataSource {

                static @NotNull Entity read(@NotNull JsonObject object) {
                    return new Entity(TextJson.string(TextJson.required(object, "entity"), "entity"));
                }

                /** {@inheritDoc} */
                @Override
                public void write(@NotNull JsonObject object) {
                    object.addProperty("entity", this.selector);
                }

            }

            /**
             * The block entity at a position.
             *
             * @param position the block position, as a command writes it
             */
            record Block(@NotNull String position) implements DataSource {

                static @NotNull Block read(@NotNull JsonObject object) {
                    return new Block(TextJson.string(TextJson.required(object, "block"), "block"));
                }

                /** {@inheritDoc} */
                @Override
                public void write(@NotNull JsonObject object) {
                    object.addProperty("block", this.position);
                }

            }

            /**
             * A command storage.
             *
             * @param id the storage's identifier
             */
            record Storage(@NotNull String id) implements DataSource {

                static @NotNull Storage read(@NotNull JsonObject object) {
                    return new Storage(TextJson.identifier(TextJson.required(object, "storage"), "storage"));
                }

                /** {@inheritDoc} */
                @Override
                public void write(@NotNull JsonObject object) {
                    object.addProperty("storage", this.id);
                }

            }

        }

    }

    /**
     * A sprite drawn inline - {@code {"type": "object", ...}}, an atlas sprite or a player's head, with a
     * component to show where the sprite cannot be drawn. Unresolved it shows that component, or the
     * bracketed name vanilla falls back to where it has none.
     *
     * @param source the sprite
     * @param fallback the component shown in place of the sprite
     */
    record Sprite(@NotNull SpriteSource source, @NotNull Optional<TextSegment> fallback) implements TextContents {

        static @NotNull Sprite read(@NotNull JsonObject object) {
            return new Sprite(SpriteSource.read(object), segment(object, "fallback"));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String type() {
            return "object";
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String plainText() {
            return this.fallback.map(TextSegment::toPlainText).orElseGet(this.source::defaultFallback);
        }

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonObject object) {
            this.source.write(object);
            this.fallback.ifPresent(fallback -> object.add("fallback", fallback.toJson()));
        }

        /**
         * The sprite a {@link Sprite} draws - named by an {@code object} member when there is one, and
         * otherwise by the first of {@link Atlas atlas} and {@link Player player} whose members it holds.
         */
        public sealed interface SpriteSource permits SpriteSource.Atlas, SpriteSource.Player {

            /**
             * Reads the sprite of a component's object.
             *
             * @param object the component's object
             * @return the sprite
             * @throws TextJsonException if the object names an unknown sprite kind, or holds none
             */
            static @NotNull SpriteSource read(@NotNull JsonObject object) {
                Optional<JsonElement> kind = TextJson.member(object, "object");

                if (kind.isPresent()) {
                    String name = TextJson.string(kind.get(), "object");

                    return switch (name) {
                        case "atlas" -> Atlas.read(object);
                        case "player" -> Player.read(object);
                        default -> throw new TextJsonException("Unknown sprite kind '%s'", name);
                    };
                }

                return firstReading(object, "sprite", List.of(Atlas::read, Player::read));
            }

            /**
             * Returns the bracketed name vanilla shows in place of the sprite.
             *
             * @return the fallback text
             */
            @NotNull String defaultFallback();

            /**
             * Writes this sprite into the component's object.
             *
             * @param object the component's object
             */
            void write(@NotNull JsonObject object);

            /**
             * A sprite of a texture atlas.
             *
             * @param atlas the atlas's identifier, {@link #DEFAULT_ATLAS} unless named
             * @param sprite the sprite's identifier
             */
            record Atlas(@NotNull String atlas, @NotNull String sprite) implements SpriteSource {

                /**
                 * The atlas a sprite is drawn from where none is named.
                 */
                public static final @NotNull String DEFAULT_ATLAS = "minecraft:blocks";

                static @NotNull Atlas read(@NotNull JsonObject object) {
                    return new Atlas(
                        TextJson.member(object, "atlas").map(element -> TextJson.identifier(element, "atlas")).orElse(DEFAULT_ATLAS),
                        TextJson.identifier(TextJson.required(object, "sprite"), "sprite")
                    );
                }

                /** {@inheritDoc} */
                @Override
                public @NotNull String defaultFallback() {
                    String sprite = shortName(this.sprite);
                    return this.atlas.equals(DEFAULT_ATLAS) ? "[" + sprite + "]" : "[" + sprite + "@" + shortName(this.atlas) + "]";
                }

                /** {@inheritDoc} */
                @Override
                public void write(@NotNull JsonObject object) {
                    if (!this.atlas.equals(DEFAULT_ATLAS))
                        object.addProperty("atlas", this.atlas);

                    object.addProperty("sprite", this.sprite);
                }

                private static @NotNull String shortName(@NotNull String id) {
                    return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
                }

            }

            /**
             * A player's head.
             *
             * @param profile the player's profile - a name, or a profile object as vanilla writes one
             * @param hat whether the head's hat layer is drawn
             */
            record Player(@NotNull JsonElement profile, boolean hat) implements SpriteSource {

                public Player {
                    if (!(profile.isJsonObject() || profile instanceof JsonPrimitive primitive && primitive.isString()))
                        throw new TextJsonException("Expected 'player' to be a name or a profile, found '%s'", profile);

                    profile = profile.deepCopy();
                }

                static @NotNull Player read(@NotNull JsonObject object) {
                    return new Player(
                        TextJson.required(object, "player"),
                        TextJson.member(object, "hat").map(element -> TextJson.bool(element, "hat")).orElse(true)
                    );
                }

                /**
                 * Returns the player's name, where the profile carries one.
                 *
                 * @return the name
                 */
                public @NotNull Optional<String> name() {
                    if (this.profile.isJsonPrimitive())
                        return Optional.of(this.profile.getAsString());

                    return TextJson.member(this.profile.getAsJsonObject(), "name")
                        .filter(name -> name instanceof JsonPrimitive primitive && primitive.isString())
                        .map(JsonElement::getAsString);
                }

                /** {@inheritDoc} */
                @Override
                public @NotNull String defaultFallback() {
                    return this.name().map(name -> "[" + name + " head]").orElse("[unknown player head]");
                }

                /** {@inheritDoc} */
                @Override
                public void write(@NotNull JsonObject object) {
                    object.add("player", this.profile.deepCopy());

                    if (!this.hat)
                        object.addProperty("hat", false);
                }

            }

        }

    }

}
