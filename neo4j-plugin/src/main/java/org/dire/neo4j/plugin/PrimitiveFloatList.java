package org.dire.neo4j.plugin;

import java.util.Arrays;

final class PrimitiveFloatList {
    private float[] values = new float[1024];
    private int size;

    void add(float value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[size++] = value;
    }

    float[] toArray() {
        return Arrays.copyOf(values, size);
    }

    float[] values() {
        return values;
    }

    int size() {
        return size;
    }
}
