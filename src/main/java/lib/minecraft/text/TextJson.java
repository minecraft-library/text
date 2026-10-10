package lib.minecraft.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.text.exception.TextJsonException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Readers that take a JSON value only in the form vanilla's JSON reading takes it - a string only from a
 * JSON string, a boolean only from {@code true} or {@code false}, a number only from a JSON number - so a
 * text component refuses what vanilla refuses rather than coercing it.
 */
@UtilityClass
@ApiStatus.Internal
public class TextJson {

    /**
     * Returns a member of an object, absent where the object lacks it or holds {@code null} under it, as
     * vanilla reads a {@code null} member as an absent one.
     *
     * @param object the object
     * @param key the member name
     * @return the member
     */
    public static @NotNull Optional<JsonElement> member(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value == null || value instanceof JsonNull ? Optional.empty() : Optional.of(value);
    }

    /**
     * Returns a member an object must hold.
     *
     * @param object the object
     * @param key the member name
     * @return the member
     * @throws TextJsonException if the member is absent or {@code null}
     */
    public static @NotNull JsonElement required(@NotNull JsonObject object, @NotNull String key) {
        return member(object, key).orElseThrow(() -> new TextJsonException("Missing '%s'", key));
    }

    /**
     * Reads a JSON object.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the object
     * @throws TextJsonException if the value is no object
     */
    public static @NotNull JsonObject object(@NotNull JsonElement element, @NotNull String what) {
        if (!element.isJsonObject())
            throw new TextJsonException("Expected '%s' to be an object, found '%s'", what, element);

        return element.getAsJsonObject();
    }

    /**
     * Reads a JSON string.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the string
     * @throws TextJsonException if the value is no JSON string
     */
    public static @NotNull String string(@NotNull JsonElement element, @NotNull String what) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString())
            throw new TextJsonException("Expected '%s' to be a string, found '%s'", what, element);

        return primitive.getAsString();
    }

    /**
     * Reads a JSON boolean.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the boolean
     * @throws TextJsonException if the value is neither {@code true} nor {@code false}
     */
    public static boolean bool(@NotNull JsonElement element, @NotNull String what) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean())
            throw new TextJsonException("Expected '%s' to be a boolean, found '%s'", what, element);

        return primitive.getAsBoolean();
    }

    /**
     * Reads a JSON number.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the number
     * @throws TextJsonException if the value is no JSON number
     */
    public static @NotNull Number number(@NotNull JsonElement element, @NotNull String what) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber())
            throw new TextJsonException("Expected '%s' to be a number, found '%s'", what, element);

        return primitive.getAsNumber();
    }

    /**
     * Reads a JSON number as an int the way vanilla's int reader does, taking the number's
     * {@link Number#intValue() int value} - so a fraction is truncated toward zero.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the int
     * @throws TextJsonException if the value is no JSON number
     */
    public static int integer(@NotNull JsonElement element, @NotNull String what) {
        return number(element, what).intValue();
    }

    /**
     * Converts a JSON number to the Java number vanilla converts it to - the narrowest of
     * {@link Byte}, {@link Short}, {@link Integer} and {@link Long} for a whole value that fits a
     * {@code long}, otherwise a {@link Float} where the value as a {@code double} survives the narrowing
     * and a {@link Double} where it does not.
     *
     * @param primitive the JSON number
     * @return the Java number
     */
    public static @NotNull Number narrowest(@NotNull JsonPrimitive primitive) {
        BigDecimal value = primitive.getAsBigDecimal();

        try {
            long whole = value.longValueExact();
            if ((byte) whole == whole) return (byte) whole;
            if ((short) whole == whole) return (short) whole;
            if ((int) whole == whole) return (int) whole;
            return whole;
        } catch (ArithmeticException fractionalOrTooWide) {
            double real = value.doubleValue();
            return (float) real == real ? (Number) (float) real : (Number) real;
        }
    }

    /**
     * Reads a namespaced identifier, defaulting the namespace to {@code minecraft} as vanilla does.
     *
     * @param element the value
     * @param what the name of the value, for the message
     * @return the identifier as {@code namespace:path}
     * @throws TextJsonException if the value is no JSON string or no valid identifier
     */
    public static @NotNull String identifier(@NotNull JsonElement element, @NotNull String what) {
        return identifier(string(element, what), what);
    }

    /**
     * Validates a namespaced identifier, defaulting the namespace to {@code minecraft} as vanilla does -
     * the namespace in {@code [a-z0-9_.-]} and other than {@code ..}, the path in {@code [a-z0-9_./-]}.
     *
     * @param value the identifier text
     * @param what the name of the value, for the message
     * @return the identifier as {@code namespace:path}
     * @throws TextJsonException if the text is no valid identifier
     */
    public static @NotNull String identifier(@NotNull String value, @NotNull String what) {
        int colon = value.indexOf(':');
        String namespace = colon > 0 ? value.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? value.substring(colon + 1) : value;

        if (namespace.equals("..") || !namespace.chars().allMatch(TextJson::isNamespaceChar))
            throw new TextJsonException("Invalid namespace in '%s': '%s'", what, value);

        if (!path.chars().allMatch(TextJson::isPathChar))
            throw new TextJsonException("Invalid path in '%s': '%s'", what, value);

        return namespace + ":" + path;
    }

    private static boolean isNamespaceChar(int c) {
        return c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.';
    }

    private static boolean isPathChar(int c) {
        return isNamespaceChar(c) || c == '/';
    }

}
