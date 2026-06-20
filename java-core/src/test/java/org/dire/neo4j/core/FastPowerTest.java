package org.dire.neo4j.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastPowerTest {
    @Test
    void rejectsNonPositiveOrNonFiniteExponents() {
        assertThrows(IllegalArgumentException.class, () -> FastPower.forExponent(0.0f));
        assertThrows(IllegalArgumentException.class, () -> FastPower.forExponent(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> FastPower.forExponent(Float.POSITIVE_INFINITY));
    }

    @Test
    void preservesExactLinearExponent() {
        FastPower power = FastPower.forExponent(1.0f);

        assertEquals(1.0f, power.approximateExponent());
        assertEquals(3.5d, power.apply(3.5d), 1.0e-12d);
    }

    @Test
    void approximatesRepresentativeExponentsWithBoundedError() {
        float[] exponents = new float[]{0.5f, 0.81f, 1.0f, 1.375f, 1.9f};
        double[] values = new double[]{1.0e-6d, 1.0e-3d, 0.1d, 1.0d, 3.0d, 10.0d, 100.0d};

        for (float exponent : exponents) {
            FastPower power = FastPower.forExponent(exponent);
            assertTrue(Math.abs(power.approximateExponent() - exponent) <= 0.03125f);
            for (double value : values) {
                double exact = Math.pow(value, exponent);
                double approximate = power.apply(value);
                double relativeError = exact == 0.0d ? Math.abs(approximate) : Math.abs((approximate - exact) / exact);
                assertTrue(relativeError <= 0.35d,
                        () -> "relative error too high for exponent=" + exponent + ", value=" + value + ": " + relativeError);
            }
        }
    }
}
