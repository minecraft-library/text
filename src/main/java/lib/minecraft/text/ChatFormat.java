package lib.minecraft.text;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.util.RegexUtil;
import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minecraft format codes ({@code k-o}, {@code r}).
 * <p>
 * These modify text style without setting a color.
 */
@Getter
@EnumLookup
public enum ChatFormat {

    OBFUSCATED('k', 0xFFFFFF),
    BOLD('l', 0xFFFF55),
    STRIKETHROUGH('m', 0xFFFFFF),
    UNDERLINE('n', 0xFFFFFF),
    ITALIC('o', 0x5555FF),
    RESET('r', 0x000000);

    public static final char SECTION_SYMBOL = '\u00a7';

    @KeyField
    private final char code;
    private final @NotNull Color shadowColor;
    private final @NotNull String toString;

    ChatFormat(char code, int shadowRgb) {
        this.code = code;
        this.shadowColor = new Color(shadowRgb);
        this.toString = new String(new char[]{ SECTION_SYMBOL, code });
    }

    /**
     * Returns {@code true} when the character is a valid color or format code.
     */
    public static boolean isValid(char code) {
        return ChatColor.isValid(code) || findByCode(code).isPresent();
    }

    /**
     * Strips all color and format codes from the given string.
     */
    public static @NotNull String stripColor(@NotNull String value) {
        return RegexUtil.strip(StringUtil.defaultString(value), RegexUtil.VANILLA_PATTERN);
    }

    /**
     * Translates alternate color code characters to the section symbol.
     */
    public static @NotNull String translateAlternateColorCodes(char altColorChar, @NotNull String value) {
        Pattern replaceAltColor = Pattern.compile(String.format("(?<!%s)%<s([0-9a-fk-orA-FK-OR])", altColorChar));
        return RegexUtil.replaceColor(value, replaceAltColor);
    }

    public @NotNull String toLegacyString() {
        return this.toString;
    }

    public @NotNull String toJsonString() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public @NotNull String toString() {
        return this.toString;
    }

}
