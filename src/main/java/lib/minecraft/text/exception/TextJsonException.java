package lib.minecraft.text.exception;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when JSON does not describe a text component the way vanilla reads one.
 */
public class TextJsonException extends RuntimeException {

    /**
     * Constructs a new {@code TextJsonException} with the given cause.
     *
     * @param cause the underlying cause
     */
    public TextJsonException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code TextJsonException} with the given message.
     *
     * @param message the detail message
     */
    public TextJsonException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code TextJsonException} with the given cause and message.
     *
     * @param cause the underlying cause
     * @param message the detail message
     */
    public TextJsonException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code TextJsonException} with a formatted message.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public TextJsonException(@NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code TextJsonException} with the given cause and a formatted message.
     *
     * @param cause the underlying cause
     * @param message the format string
     * @param args the format arguments
     */
    public TextJsonException(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
