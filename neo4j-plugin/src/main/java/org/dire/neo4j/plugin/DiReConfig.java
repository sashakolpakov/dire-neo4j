package org.dire.neo4j.plugin;

import org.dire.neo4j.core.InitializationMode;
import org.dire.neo4j.core.LayoutConfig;
import org.dire.neo4j.core.RelationshipMode;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DiReConfig {
    final String nodeQuery;
    final String relationshipQuery;
    final Map<String, Object> parameters;
    final List<String> writeProperties;
    final List<String> writeInitialProperties;
    final List<String> warmStartProperties;
    final LayoutConfig layoutConfig;
    final Long maxProjectionBytes;
    final boolean includeEmbedding;

    private DiReConfig(
            String nodeQuery,
            String relationshipQuery,
            Map<String, Object> parameters,
            List<String> writeProperties,
            List<String> writeInitialProperties,
            List<String> warmStartProperties,
            LayoutConfig layoutConfig,
            Long maxProjectionBytes,
            boolean includeEmbedding) {
        this.nodeQuery = nodeQuery;
        this.relationshipQuery = relationshipQuery;
        this.parameters = parameters;
        this.writeProperties = writeProperties;
        this.writeInitialProperties = writeInitialProperties;
        this.warmStartProperties = warmStartProperties;
        this.layoutConfig = layoutConfig;
        this.maxProjectionBytes = maxProjectionBytes;
        this.includeEmbedding = includeEmbedding;
    }

    static DiReConfig parse(Map<String, Object> config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        String nodeQuery = string(config, "nodeQuery", null);
        String relationshipQuery = string(config, "relationshipQuery", null);
        if (nodeQuery == null || nodeQuery.isBlank()) {
            throw new IllegalArgumentException("config.nodeQuery is required");
        }
        if (relationshipQuery == null || relationshipQuery.isBlank()) {
            throw new IllegalArgumentException("config.relationshipQuery is required");
        }

        int dimensions = integer(config, "dimensions", 2);
        List<String> writeProperties = stringList(config, "writeProperties", defaultWriteProperties(dimensions));
        if (writeProperties.size() < dimensions) {
            throw new IllegalArgumentException("writeProperties must contain at least one property per dimension");
        }
        List<String> writeInitialProperties = stringList(config, "writeInitialProperties", defaultWriteInitialProperties(dimensions));
        if (!writeInitialProperties.isEmpty() && writeInitialProperties.size() < dimensions) {
            throw new IllegalArgumentException("writeInitialProperties must contain at least one property per dimension");
        }
        List<String> warmStartProperties = stringList(config, "warmStartProperties", writeProperties);

        LayoutConfig layoutConfig = LayoutConfig.builder()
                .dimensions(dimensions)
                .iterations(integer(config, "iterations", 200))
                .randomSeed(longValue(config, "randomSeed", 42L))
                .initializationMode(initializationMode(string(config, "initialization", "spectral")))
                .relationshipMode(relationshipMode(string(config, "relationshipMode", "undirected")))
                .minDist(floatValue(config, "minDist", 1.0e-2f))
                .spread(floatValue(config, "spread", 1.0f))
                .cutoff(floatValue(config, "cutoff", 42.0f))
                .learningRate(floatValue(config, "learningRate", 1.0f))
                .attractionStrength(floatValue(config, "attractionStrength", 1.0f))
                .repulsionStrength(floatValue(config, "repulsionStrength", 1.0f))
                .negativeSamples(integer(config, "negativeSamples", 16))
                .concurrency(integer(config, "concurrency", LayoutConfig.defaultConcurrency()))
                .fastKernel(bool(config, "fastKernel", false))
                .build();

        return new DiReConfig(
                nodeQuery,
                relationshipQuery,
                parameters(config),
                writeProperties,
                writeInitialProperties,
                warmStartProperties,
                layoutConfig,
                positiveOptionalLong(config, "maxProjectionBytes"),
                bool(config, "includeEmbedding", false));
    }

    static EstimateInput parseEstimate(Map<String, Object> config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        int dimensions = integer(config, "dimensions", 2);
        RelationshipMode relationshipMode = relationshipMode(string(config, "relationshipMode", "undirected"));
        InitializationMode initializationMode = initializationMode(string(config, "initialization", "spectral"));
        return new EstimateInput(
                string(config, "nodeQuery", null),
                string(config, "relationshipQuery", null),
                parameters(config),
                optionalLong(config, "nodeCount"),
                optionalLong(config, "relationshipCount"),
                dimensions,
                relationshipMode,
                initializationMode,
                bool(config, "includeEmbedding", false));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Map<String, Object> config) {
        Object value = config.get("parameters");
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("parameters must be a map");
        }
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private static int integer(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return number.intValue();
    }

    private static boolean bool(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return flag;
    }

    private static long longValue(Map<String, Object> config, String key, long defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return number.longValue();
    }

    private static Long optionalLong(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return number.longValue();
    }

    private static Long positiveOptionalLong(Map<String, Object> config, String key) {
        Long value = optionalLong(config, key);
        if (value == null) {
            return null;
        }
        if (value <= 0L) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static float floatValue(Map<String, Object> config, String key, float defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return number.floatValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> config, String key, List<String> defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }
        for (Object item : list) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new IllegalArgumentException(key + " must be a list of non-empty strings");
            }
        }
        return (List<String>) list;
    }

    private static List<String> defaultWriteProperties(int dimensions) {
        if (dimensions == 3) {
            return List.of("dire_x", "dire_y", "dire_z");
        }
        return List.of("dire_x", "dire_y");
    }

    private static List<String> defaultWriteInitialProperties(int dimensions) {
        if (dimensions == 3) {
            return List.of("dire_initial_x", "dire_initial_y", "dire_initial_z");
        }
        return List.of("dire_initial_x", "dire_initial_y");
    }

    private static InitializationMode initializationMode(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (normalized) {
            case "spectral", "laplacian" -> InitializationMode.SPECTRAL;
            case "warm_start", "warmstart" -> InitializationMode.WARM_START;
            case "random" -> InitializationMode.RANDOM;
            default -> throw new IllegalArgumentException("unknown initialization: " + value);
        };
    }

    private static RelationshipMode relationshipMode(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (normalized) {
            case "undirected" -> RelationshipMode.UNDIRECTED;
            case "directed" -> RelationshipMode.DIRECTED;
            default -> throw new IllegalArgumentException("unknown relationshipMode: " + value);
        };
    }

    record EstimateInput(
            String nodeQuery,
            String relationshipQuery,
            Map<String, Object> parameters,
            Long nodeCount,
            Long relationshipCount,
            int dimensions,
            RelationshipMode relationshipMode,
            InitializationMode initializationMode,
            boolean includeEmbedding) {
    }
}
