package org.dire.neo4j.core;

final class SplitMix64 {
    private SplitMix64() {
    }

    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static float signedUnit(long value) {
        long mixed = mix(value);
        int high = (int) (mixed >>> 40);
        float unit = high / (float) (1 << 24);
        return 2.0f * unit - 1.0f;
    }

    static int bounded(long value, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long mixed = mix(value);
        long positive = mixed >>> 1;
        return (int) (positive % bound);
    }
}
