package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * A {@link RandomGenerator} over a seeded {@link Random} that records the bound of every
 * {@code nextInt(int)} call and counts every other draw, so a test can see how many values a caller
 * took and from what range.
 * <p>
 * Every inherited draw other than {@code nextInt(int)} goes through {@link #nextLong()}, so
 * {@link #otherDraws()} counts all of them.
 */
final class CountingRandom implements RandomGenerator {

    /** The generator every draw is answered from. */
    private final @NotNull Random delegate;

    /** The bound of each {@code nextInt(int)} call, in call order. */
    private final @NotNull List<Integer> bounds = new ArrayList<>();

    /** The number of draws made through anything but {@code nextInt(int)}. */
    private int otherDraws;

    /**
     * Constructs a new {@code CountingRandom} drawing the values {@code new Random(seed)} draws.
     *
     * @param seed the seed of the underlying generator
     */
    CountingRandom(long seed) {
        this.delegate = new Random(seed);
    }

    @Override
    public int nextInt(int bound) {
        this.bounds.add(bound);
        return this.delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
        this.otherDraws++;
        return this.delegate.nextLong();
    }

    /**
     * Returns the bound of each {@code nextInt(int)} call so far, in call order.
     *
     * @return a copy of the recorded bounds
     */
    @NotNull List<Integer> bounds() {
        return List.copyOf(this.bounds);
    }

    /**
     * Returns the number of draws made so far through anything but {@code nextInt(int)}.
     *
     * @return the count of other draws
     */
    int otherDraws() {
        return this.otherDraws;
    }

}
