package org.dire.neo4j.plugin;

import java.util.Arrays;

final class PrimitiveLongList {
    private long[] values = new long[1024];
    private int size;

    void add(long value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[size++] = value;
    }

    long[] toArray() {
        return Arrays.copyOf(values, size);
    }

    int size() {
        return size;
    }
}
