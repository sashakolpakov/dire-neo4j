package org.dire.neo4j.core;

final class KernelParameters {
    final float a;
    final float b;

    private KernelParameters(float a, float b) {
        this.a = a;
        this.b = b;
    }

    static KernelParameters fit(float minDist, float spread) {
        double logA = 0.0;
        double logB = 0.0;
        double stepA = 1.0;
        double stepB = 0.5;
        double best = objective(logA, logB, minDist, spread);

        for (int iter = 0; iter < 80; iter++) {
            boolean improved = false;
            double bestA = logA;
            double bestB = logB;
            for (int da = -1; da <= 1; da++) {
                for (int db = -1; db <= 1; db++) {
                    if (da == 0 && db == 0) {
                        continue;
                    }
                    double candidateA = logA + da * stepA;
                    double candidateB = logB + db * stepB;
                    double score = objective(candidateA, candidateB, minDist, spread);
                    if (score < best) {
                        best = score;
                        bestA = candidateA;
                        bestB = candidateB;
                        improved = true;
                    }
                }
            }
            if (improved) {
                logA = bestA;
                logB = bestB;
            } else {
                stepA *= 0.65;
                stepB *= 0.65;
            }
        }

        return new KernelParameters((float) Math.exp(logA), (float) Math.exp(logB));
    }

    private static double objective(double logA, double logB, float minDist, float spread) {
        double a = Math.exp(logA);
        double b = Math.exp(logB);
        double maxX = 3.0 * spread;
        double total = 0.0;
        for (int i = 0; i < 300; i++) {
            double x = maxX * i / 299.0;
            double target = x < minDist ? 1.0 : Math.exp(-(x - minDist) / spread);
            double model = 1.0 / (1.0 + a * Math.pow(x, 2.0 * b));
            double error = model - target;
            total += error * error;
        }
        return total;
    }
}
