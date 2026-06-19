package org.dire.neo4j.core;

final class FastPower {
    private static final int MAX_ROOT_STEPS = 5;
    private static final int MAX_NUMERATOR = 128;

    private final int numerator;
    private final int rootSteps;
    private final float approximateExponent;

    private FastPower(int numerator, int rootSteps, float approximateExponent) {
        this.numerator = numerator;
        this.rootSteps = rootSteps;
        this.approximateExponent = approximateExponent;
    }

    static FastPower forExponent(float exponent) {
        if (!Float.isFinite(exponent) || exponent <= 0.0f) {
            throw new IllegalArgumentException("exponent must be finite and positive");
        }
        int bestNumerator = 1;
        int bestRootSteps = 0;
        float bestApproximation = 1.0f;
        double bestError = Double.POSITIVE_INFINITY;
        int bestCost = Integer.MAX_VALUE;
        for (int rootSteps = 0; rootSteps <= MAX_ROOT_STEPS; rootSteps++) {
            int denominator = 1 << rootSteps;
            int numerator = Math.max(1, Math.min(MAX_NUMERATOR, Math.round(exponent * denominator)));
            float approximation = numerator / (float) denominator;
            double error = Math.abs(approximation - exponent);
            int cost = rootSteps + integerPowCost(numerator);
            if (error < bestError || (error == bestError && cost < bestCost)) {
                bestError = error;
                bestCost = cost;
                bestNumerator = numerator;
                bestRootSteps = rootSteps;
                bestApproximation = approximation;
            }
        }
        return new FastPower(bestNumerator, bestRootSteps, bestApproximation);
    }

    double apply(double value) {
        double rooted = value;
        for (int i = 0; i < rootSteps; i++) {
            rooted = Math.sqrt(rooted);
        }
        return integerPow(rooted, numerator);
    }

    float approximateExponent() {
        return approximateExponent;
    }

    private static double integerPow(double value, int exponent) {
        double result = 1.0;
        double factor = value;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result *= factor;
            }
            remaining >>= 1;
            if (remaining > 0) {
                factor *= factor;
            }
        }
        return result;
    }

    private static int integerPowCost(int exponent) {
        int cost = 0;
        int remaining = exponent;
        while (remaining > 1) {
            cost++;
            remaining >>= 1;
        }
        return cost;
    }
}
