package org.dire.neo4j.core;

import java.util.Objects;

public final class LayoutConfig {
    private final int dimensions;
    private final int iterations;
    private final long randomSeed;
    private final InitializationMode initializationMode;
    private final RelationshipMode relationshipMode;
    private final float minDist;
    private final float spread;
    private final float cutoff;
    private final float learningRate;
    private final float attractionStrength;
    private final float repulsionStrength;
    private final int negativeSamples;
    private final int concurrency;
    private final boolean fastKernel;
    private final float spectralTolerance;
    private final int spectralMinIterations;
    private final int spectralMaxIterations;

    private LayoutConfig(Builder builder) {
        this.dimensions = builder.dimensions;
        this.iterations = builder.iterations;
        this.randomSeed = builder.randomSeed;
        this.initializationMode = Objects.requireNonNull(builder.initializationMode);
        this.relationshipMode = Objects.requireNonNull(builder.relationshipMode);
        this.minDist = builder.minDist;
        this.spread = builder.spread;
        this.cutoff = builder.cutoff;
        this.learningRate = builder.learningRate;
        this.attractionStrength = builder.attractionStrength;
        this.repulsionStrength = builder.repulsionStrength;
        this.negativeSamples = builder.negativeSamples;
        this.concurrency = builder.concurrency;
        this.fastKernel = builder.fastKernel;
        this.spectralTolerance = builder.spectralTolerance;
        this.spectralMinIterations = builder.spectralMinIterations;
        this.spectralMaxIterations = builder.spectralMaxIterations;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static int defaultConcurrency() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
    }

    public int dimensions() {
        return dimensions;
    }

    public int iterations() {
        return iterations;
    }

    public long randomSeed() {
        return randomSeed;
    }

    public InitializationMode initializationMode() {
        return initializationMode;
    }

    public RelationshipMode relationshipMode() {
        return relationshipMode;
    }

    public float minDist() {
        return minDist;
    }

    public float spread() {
        return spread;
    }

    public float cutoff() {
        return cutoff;
    }

    public float learningRate() {
        return learningRate;
    }

    public float attractionStrength() {
        return attractionStrength;
    }

    public float repulsionStrength() {
        return repulsionStrength;
    }

    public int negativeSamples() {
        return negativeSamples;
    }

    public int concurrency() {
        return concurrency;
    }

    public boolean fastKernel() {
        return fastKernel;
    }

    public float spectralTolerance() {
        return spectralTolerance;
    }

    public int spectralMinIterations() {
        return spectralMinIterations;
    }

    public int spectralMaxIterations() {
        return spectralMaxIterations;
    }

    private void validate() {
        if (dimensions < 2 || dimensions > 3) {
            throw new IllegalArgumentException("dimensions must be 2 or 3");
        }
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be non-negative");
        }
        if (!Float.isFinite(minDist) || minDist < 0.0f) {
            throw new IllegalArgumentException("minDist must be finite and non-negative");
        }
        if (!Float.isFinite(spread) || spread <= 0.0f) {
            throw new IllegalArgumentException("spread must be finite and positive");
        }
        if (!Float.isFinite(cutoff) || cutoff <= 0.0f) {
            throw new IllegalArgumentException("cutoff must be finite and positive");
        }
        if (!Float.isFinite(learningRate) || learningRate < 0.0f) {
            throw new IllegalArgumentException("learningRate must be finite and non-negative");
        }
        if (!Float.isFinite(attractionStrength) || attractionStrength < 0.0f) {
            throw new IllegalArgumentException("attractionStrength must be finite and non-negative");
        }
        if (!Float.isFinite(repulsionStrength) || repulsionStrength < 0.0f) {
            throw new IllegalArgumentException("repulsionStrength must be finite and non-negative");
        }
        if (negativeSamples < 0) {
            throw new IllegalArgumentException("negativeSamples must be non-negative");
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        if (!Float.isFinite(spectralTolerance) || spectralTolerance < 0.0f || spectralTolerance > 1.0f) {
            throw new IllegalArgumentException("spectralTolerance must be finite and between 0 and 1");
        }
        if (spectralMinIterations <= 0) {
            throw new IllegalArgumentException("spectralMinIterations must be positive");
        }
        if (spectralMaxIterations < spectralMinIterations) {
            throw new IllegalArgumentException("spectralMaxIterations must be at least spectralMinIterations");
        }
    }

    public static final class Builder {
        private int dimensions = 2;
        private int iterations = 200;
        private long randomSeed = 42L;
        private InitializationMode initializationMode = InitializationMode.SPECTRAL;
        private RelationshipMode relationshipMode = RelationshipMode.UNDIRECTED;
        private float minDist = 1.0e-2f;
        private float spread = 1.0f;
        private float cutoff = 42.0f;
        private float learningRate = 1.0f;
        private float attractionStrength = 1.0f;
        private float repulsionStrength = 1.0f;
        private int negativeSamples = 16;
        private int concurrency = LayoutConfig.defaultConcurrency();
        private boolean fastKernel = false;
        private float spectralTolerance = 0.0f;
        private int spectralMinIterations = 8;
        private int spectralMaxIterations = 160;

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder randomSeed(long randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        public Builder initializationMode(InitializationMode initializationMode) {
            this.initializationMode = initializationMode;
            return this;
        }

        public Builder relationshipMode(RelationshipMode relationshipMode) {
            this.relationshipMode = relationshipMode;
            return this;
        }

        public Builder minDist(float minDist) {
            this.minDist = minDist;
            return this;
        }

        public Builder spread(float spread) {
            this.spread = spread;
            return this;
        }

        public Builder cutoff(float cutoff) {
            this.cutoff = cutoff;
            return this;
        }

        public Builder learningRate(float learningRate) {
            this.learningRate = learningRate;
            return this;
        }

        public Builder attractionStrength(float attractionStrength) {
            this.attractionStrength = attractionStrength;
            return this;
        }

        public Builder repulsionStrength(float repulsionStrength) {
            this.repulsionStrength = repulsionStrength;
            return this;
        }

        public Builder negativeSamples(int negativeSamples) {
            this.negativeSamples = negativeSamples;
            return this;
        }

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder fastKernel(boolean fastKernel) {
            this.fastKernel = fastKernel;
            return this;
        }

        public Builder spectralTolerance(float spectralTolerance) {
            this.spectralTolerance = spectralTolerance;
            return this;
        }

        public Builder spectralMinIterations(int spectralMinIterations) {
            this.spectralMinIterations = spectralMinIterations;
            return this;
        }

        public Builder spectralMaxIterations(int spectralMaxIterations) {
            this.spectralMaxIterations = spectralMaxIterations;
            return this;
        }

        public LayoutConfig build() {
            return new LayoutConfig(this);
        }
    }
}
