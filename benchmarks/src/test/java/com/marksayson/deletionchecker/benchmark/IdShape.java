package com.marksayson.deletionchecker.benchmark;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The identifier shapes the benchmark sweeps over. All are ASCII and within the dataset's 36-byte
 * limit.
 */
enum IdShape {

    /** Canonical hyphenated UUID, 36 bytes — the wide, high-entropy case. */
    UUID {
        @Override
        String next(final SplittableRandom random) {
            return new java.util.UUID(random.nextLong(), random.nextLong()).toString();
        }
    },

    /** 16 characters from {@code [A-Za-z0-9]} — a compact opaque token. */
    ALNUM16 {
        @Override
        String next(final SplittableRandom random) {
            return randomAlnum(random, 16);
        }
    },

    /**
     * {@code "customer-"} plus 6 characters from {@code [A-Za-z0-9]} (15 bytes): a human-readable
     * ID whose entropy is concentrated in a short suffix, so the whole set shares a 9-byte prefix —
     * the case that stresses the two-level prefix index.
     */
    CUSTOMER {
        @Override
        String next(final SplittableRandom random) {
            return "customer-" + randomAlnum(random, 6);
        }
    };

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    abstract String next(SplittableRandom random);

    private static String randomAlnum(final SplittableRandom random, final int length) {
        final char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }

    /**
     * Returns {@code count} distinct identifiers of this shape, generated deterministically from
     * {@code seed}.
     */
    List<String> unique(final long seed, final int count) {
        final SplittableRandom random = new SplittableRandom(seed);
        final Set<String> seen = HashSet.newHashSet(count);
        final List<String> out = new ArrayList<>(count);
        while (out.size() < count) {
            final String id = next(random);
            if (seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Returns {@code count} identifiers of this shape that are not in {@code excluded}. Not
     * de-duplicated among themselves — at these key spaces the collision rate is negligible and a
     * repeated negative probe is harmless.
     */
    List<String> absent(final long seed, final int count, final Set<String> excluded) {
        final SplittableRandom random = new SplittableRandom(seed);
        final List<String> out = new ArrayList<>(count);
        while (out.size() < count) {
            final String id = next(random);
            if (!excluded.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    String flag() {
        return name().toLowerCase(Locale.ROOT);
    }

    static IdShape fromFlag(final String flag) {
        return valueOf(flag.trim().toUpperCase(Locale.ROOT));
    }
}
