package org.dire.neo4j.plugin;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.kernel.database.DefaultDatabaseResolver;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Path("/")
public class DireViewResource {
    private static final String DEFAULT_NODE_QUERY = """
            WITH 20000 AS sampleSize,
                 ['dire_initial', 'dire_fast', 'dire', 'dire_balanced', 'dire_wide', 'spectral'] AS coordinateRuns
            MATCH (n)
            WHERE (n.x IS NOT NULL AND n.y IS NOT NULL)
               OR any(run IN coordinateRuns WHERE n[run + '_x'] IS NOT NULL AND n[run + '_y'] IS NOT NULL)
            WITH sampleSize, coordinateRuns, count(n) AS sourceTotal
            MATCH (n)
            WHERE (n.x IS NOT NULL AND n.y IS NOT NULL)
               OR any(run IN coordinateRuns WHERE n[run + '_x'] IS NOT NULL AND n[run + '_y'] IS NOT NULL)
            WITH sampleSize, sourceTotal, n, rand() AS draw
            ORDER BY draw
            WITH sampleSize, sourceTotal, collect(n) AS sampled
            UNWIND sampled[..sampleSize] AS n
            RETURN id(n) AS idx,
                   coalesce(n.name, n.title, toString(id(n))) AS name,
                   coalesce(n.group, head(labels(n)), 'Graph') AS group,
                   n.localIndex AS localIndex,
                   sourceTotal AS sourceTotal
            """;

    private static final String DEFAULT_EDGE_QUERY = """
            MATCH (a)-[r]->(b)
            WHERE id(a) IN $visibleIds AND id(b) IN $visibleIds
            RETURN id(a) AS source, id(b) AS target,
                   coalesce(r.weight, 1.0) AS weight,
                   CASE coalesce(r.kind, '') WHEN 'bridge' THEN 'bridge' ELSE 'local' END AS kind
            ORDER BY source, target
            """;

    private static final List<RunColumns> KNOWN_RUNS = List.of(
            new RunColumns("dire_initial", "Initial", "Coordinates before DiRe refinement", "dire_initial_x", "dire_initial_y"),
            new RunColumns("dire", "DiRe", "Stored dire_x/dire_y coordinates", "dire_x", "dire_y"),
            new RunColumns("dire_fast", "DiRe fast", "Short DiRe run", "dire_fast_x", "dire_fast_y"),
            new RunColumns("dire_balanced", "DiRe balanced", "Reference DiRe run", "dire_balanced_x", "dire_balanced_y"),
            new RunColumns("dire_wide", "DiRe wide", "Higher-separation DiRe run", "dire_wide_x", "dire_wide_y"),
            new RunColumns("spectral", "Spectral init", "Laplacian start / initialization", "spectral_x", "spectral_y"),
            new RunColumns("custom", "Cypher x/y", "Coordinates returned as x and y", "x", "y")
    );

    private static final List<String> PALETTE = List.of(
            "#2A6FBB", "#C23B3B", "#2C8C62", "#7A4BA0",
            "#C96F22", "#2B8AA0", "#B4387A", "#6B7280",
            "#8B8F00", "#D65F00", "#009E73", "#CC79A7"
    );

    @Context
    public GraphDatabaseService db;

    @Context
    public DatabaseManagementService dbms;

    @Context
    public DefaultDatabaseResolver defaultDatabaseResolver;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        return textResource("dire-viewer/index.html", MediaType.TEXT_HTML);
    }

    @GET
    @Path("viewer-script")
    @Produces("application/javascript")
    public Response script() {
        return textResource("dire-viewer/dire-viewer.js", "application/javascript");
    }

    @GET
    @Path("api/data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response defaultData() {
        try {
            return json(loadPayload(DEFAULT_NODE_QUERY, DEFAULT_EDGE_QUERY));
        } catch (RuntimeException error) {
            return error(error);
        }
    }

    @POST
    @Path("api/query")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response query(
            @FormParam("nodeQuery") String nodeQuery,
            @FormParam("edgeQuery") String edgeQuery) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "Custom viewer Cypher is disabled. Use GET /api/data for the built-in read-only viewer payload.");
        payload.put("error", "CustomCypherDisabled");
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(toJson(payload))
                .header("Cache-Control", "no-store")
                .build();
    }

    private Response textResource(String name, String mediaType) {
        try (InputStream stream = DireViewResource.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new WebApplicationException("Missing plugin resource " + name, Response.Status.NOT_FOUND);
            }
            return Response.ok(new String(stream.readAllBytes(), StandardCharsets.UTF_8), mediaType)
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Response json(Map<String, Object> payload) {
        return Response.ok(toJson(payload), MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    private Response error(RuntimeException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message(error));
        payload.put("error", error.getClass().getSimpleName());
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(toJson(payload))
                .header("Cache-Control", "no-store")
                .build();
    }

    private Map<String, Object> loadPayload(String nodeQuery, String edgeQuery) {
        List<Map<String, Object>> nodeRows = executeRows(nodeQuery);
        long sourceNodes = sourceNodes(nodeRows);
        Set<Long> visibleIds = nodeIds(nodeRows);
        List<Map<String, Object>> hydratedNodeRows = hydrateCoordinates(nodeRows);
        List<Map<String, Object>> edgeRows = executeRows(edgeQuery, Map.of("visibleIds", new ArrayList<>(visibleIds)));
        List<Map<String, Object>> metricRows = executeRows("""
                MATCH (r:EmbeddingRun)
                RETURN r.key AS key, r.name AS name, r.description AS description,
                       r.stress AS stress, r.meanEdgeLength AS meanEdgeLength,
                       r.rank AS rank
                ORDER BY coalesce(r.rank, 999), r.key
                """);
        String activeRun = activeRun();

        Map<String, Map<String, Object>> metrics = metrics(metricRows);
        Map<String, Map<String, Object>> runs = new LinkedHashMap<>();
        Set<String> columns = allColumns(hydratedNodeRows);
        for (RunColumns run : KNOWN_RUNS) {
            if (columns.contains(run.xColumn) && columns.contains(run.yColumn) && hasCoordinates(hydratedNodeRows, run)) {
                metrics.computeIfAbsent(run.key, ignored -> defaultMetric(run));
                runs.put(run.key, runData(hydratedNodeRows, run));
            }
        }
        for (String key : metrics.keySet()) {
            if (!runs.containsKey(key)) {
                RunColumns run = runForMetric(key, metrics.get(key));
                if (columns.contains(run.xColumn) && columns.contains(run.yColumn) && hasCoordinates(hydratedNodeRows, run)) {
                    runs.put(key, runData(hydratedNodeRows, run));
                }
            }
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Node query must return at least one coordinate pair, for example dire_x/dire_y or x/y.");
        }

        List<Map<String, Object>> edges = edgeData(edgeRows);

        List<String> order = new ArrayList<>(runs.keySet());
        if (!runs.containsKey(activeRun)) {
            activeRun = order.contains("dire") ? "dire" : order.contains("dire_balanced") ? "dire_balanced" : order.get(0);
        }

        List<Map<String, Object>> activeNodes = typedList(runs.get(activeRun).get("nodes"));
        Map<String, Long> groups = groupCounts(activeNodes);
        Map<String, String> colors = colors(groups.keySet());
        Map<String, Long> edgeKinds = edgeCounts(edges);
        Map<String, Object> components = components(activeNodes, edges);

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("nodeQuery", nodeQuery);
        sample.put("edgeQuery", edgeQuery);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runs", runs);
        payload.put("edges", edges);
        payload.put("metrics", orderedMetrics(metrics, order));
        payload.put("order", order);
        payload.put("groups", groups);
        payload.put("edgeKinds", edgeKinds);
        payload.put("components", components);
        payload.put("sourceNodes", sourceNodes);
        payload.put("totalNodes", activeNodes.size());
        payload.put("totalEdges", edges.size());
        payload.put("activeRun", activeRun);
        payload.put("colors", colors);
        payload.put("sample", sample);
        return payload;
    }

    private List<Map<String, Object>> executeRows(String query) {
        return executeRows(query, Map.of());
    }

    private List<Map<String, Object>> executeRows(String query, Map<String, Object> parameters) {
        try (Transaction tx = database().beginTx(); Result result = tx.execute(query, parameters)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(new LinkedHashMap<>(result.next()));
            }
            return rows;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cypher failed: " + message(error), error);
        }
    }

    private Set<Long> nodeIds(List<Map<String, Object>> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Number id = number(row.get("idx"));
            if (id != null) {
                ids.add(id.longValue());
            }
        }
        return ids;
    }

    private long sourceNodes(List<Map<String, Object>> rows) {
        long sourceNodes = rows.size();
        for (Map<String, Object> row : rows) {
            Number count = number(row.get("sourceTotal"));
            if (count != null) {
                sourceNodes = Math.max(sourceNodes, count.longValue());
            }
        }
        return sourceNodes;
    }

    private List<Map<String, Object>> hydrateCoordinates(List<Map<String, Object>> rows) {
        Set<Long> ids = nodeIds(rows);
        if (ids.isEmpty()) {
            return rows;
        }
        Map<Long, Map<String, Object>> coordinatesById = new LinkedHashMap<>();
        for (Map<String, Object> row : executeRows("""
                MATCH (n)
                WHERE id(n) IN $visibleIds
                RETURN id(n) AS idx,
                       n.dire_initial_x AS dire_initial_x, n.dire_initial_y AS dire_initial_y,
                       n.spectral_x AS spectral_x, n.spectral_y AS spectral_y,
                       n.dire_fast_x AS dire_fast_x, n.dire_fast_y AS dire_fast_y,
                       n.dire_balanced_x AS dire_balanced_x, n.dire_balanced_y AS dire_balanced_y,
                       n.dire_wide_x AS dire_wide_x, n.dire_wide_y AS dire_wide_y,
                       n.dire_x AS dire_x, n.dire_y AS dire_y,
                       n.x AS x, n.y AS y
                """, Map.of("visibleIds", new ArrayList<>(ids)))) {
            Number id = number(row.get("idx"));
            if (id != null) {
                coordinatesById.put(id.longValue(), row);
            }
        }
        List<Map<String, Object>> hydrated = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Number id = number(copy.get("idx"));
            if (id != null) {
                Map<String, Object> coordinates = coordinatesById.get(id.longValue());
                if (coordinates != null) {
                    copy.putAll(coordinates);
                }
            }
            hydrated.add(copy);
        }
        return hydrated;
    }

    private GraphDatabaseService database() {
        if (db != null) {
            return db;
        }
        if (dbms == null) {
            throw new IllegalStateException("Neo4j DatabaseManagementService is not available to the DiRe extension.");
        }
        return dbms.database(defaultDatabaseName());
    }

    private String defaultDatabaseName() {
        if (defaultDatabaseResolver == null) {
            return "neo4j";
        }
        try {
            String resolved = defaultDatabaseResolver.defaultDatabase("");
            return resolved == null || resolved.isBlank() ? "neo4j" : resolved;
        } catch (RuntimeException ignored) {
            return "neo4j";
        }
    }

    private String activeRun() {
        try {
            List<Map<String, Object>> rows = executeRows("""
                    MATCH (v:DireView)
                    RETURN v.run AS run
                    ORDER BY v.name
                    LIMIT 1
                    """);
            if (!rows.isEmpty() && rows.get(0).get("run") != null) {
                return rows.get(0).get("run").toString();
            }
        } catch (WebApplicationException ignored) {
            return "dire";
        }
        return "dire";
    }

    private Map<String, Map<String, Object>> metrics(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object rawKey = row.get("key");
            if (rawKey == null) {
                continue;
            }
            String key = rawKey.toString();
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("name", stringOr(row.get("name"), key));
            metric.put("description", stringOr(row.get("description"), "Stored Neo4j coordinate pair"));
            metric.put("stress", numberOrNull(row.get("stress")));
            metric.put("meanEdgeLength", numberOrNull(row.get("meanEdgeLength")));
            metrics.put(key, metric);
        }
        return metrics;
    }

    private Map<String, Object> defaultMetric(RunColumns run) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("name", run.name);
        metric.put("description", run.description);
        metric.put("stress", null);
        metric.put("meanEdgeLength", null);
        return metric;
    }

    private Map<String, Map<String, Object>> orderedMetrics(
            Map<String, Map<String, Object>> metrics,
            List<String> order) {
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String key : order) {
            ordered.put(key, metrics.get(key));
        }
        return ordered;
    }

    private RunColumns runForMetric(String key, Map<String, Object> metric) {
        String x = key + "_x";
        String y = key + "_y";
        String name = stringOr(metric.get("name"), key);
        String description = stringOr(metric.get("description"), "Stored Neo4j coordinate pair");
        return new RunColumns(key, name, description, x, y);
    }

    private Map<String, Object> runData(List<Map<String, Object>> rows, RunColumns run) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Double x = numberOrNull(row.get(run.xColumn));
            Double y = numberOrNull(row.get(run.yColumn));
            if (x == null || y == null) {
                continue;
            }
            long idx = longOr(row.get("idx"), i);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("idx", idx);
            node.put("name", stringOr(row.get("name"), Long.toString(idx)));
            node.put("group", stringOr(row.get("group"), "Graph"));
            node.put("x", x);
            node.put("y", y);
            nodes.add(node);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        return data;
    }

    private boolean hasCoordinates(List<Map<String, Object>> rows, RunColumns run) {
        for (Map<String, Object> row : rows) {
            if (numberOrNull(row.get(run.xColumn)) != null && numberOrNull(row.get(run.yColumn)) != null) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> edgeData(List<Map<String, Object>> rows) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Number source = number(row.get("source"));
            Number target = number(row.get("target"));
            if (source == null || target == null) {
                continue;
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("source", source.longValue());
            edge.put("target", target.longValue());
            edge.put("weight", numberOr(row.get("weight"), 1.0d));
            edge.put("kind", stringOr(row.get("kind"), "local"));
            edges.add(edge);
        }
        return edges;
    }

    private Set<String> allColumns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return columns;
    }

    private Map<String, Long> groupCounts(List<Map<String, Object>> nodes) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String group = node.get("group").toString();
            counts.put(group, counts.getOrDefault(group, 0L) + 1L);
        }
        return counts;
    }

    private Map<String, String> colors(Set<String> groups) {
        Map<String, String> colors = new LinkedHashMap<>();
        Map<String, String> preferred = Map.ofEntries(
                Map.entry("Algebra", "#2A6FBB"),
                Map.entry("Topology", "#C23B3B"),
                Map.entry("Geometry", "#2C8C62"),
                Map.entry("Analysis", "#7A4BA0"),
                Map.entry("0", "#2A6FBB"),
                Map.entry("1", "#E69F00"),
                Map.entry("2", "#009E73"),
                Map.entry("3", "#D55E00"),
                Map.entry("4", "#7A4BA0"),
                Map.entry("5", "#8B5A2B"),
                Map.entry("6", "#56B4E9"),
                Map.entry("7", "#CC79A7"),
                Map.entry("8", "#6B7280"),
                Map.entry("9", "#8B8F00")
        );
        int index = 0;
        for (String group : groups) {
            colors.put(group, preferred.getOrDefault(group, PALETTE.get(index % PALETTE.size())));
            index++;
        }
        return colors;
    }

    private Map<String, Long> edgeCounts(List<Map<String, Object>> edges) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String kind = edge.get("kind").toString();
            counts.put(kind, counts.getOrDefault(kind, 0L) + 1L);
        }
        return counts;
    }

    private Map<String, Object> components(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<Long, Integer> offsets = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            offsets.put(number(nodes.get(i).get("idx")).longValue(), i);
        }
        int[] parent = new int[nodes.size()];
        int[] rank = new int[nodes.size()];
        Arrays.setAll(parent, i -> i);
        for (Map<String, Object> edge : edges) {
            Integer source = offsets.get(number(edge.get("source")).longValue());
            Integer target = offsets.get(number(edge.get("target")).longValue());
            if (source != null && target != null) {
                union(parent, rank, source, target);
            }
        }
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) {
            int root = find(parent, i);
            counts.put(root, counts.getOrDefault(root, 0) + 1);
        }
        int largest = 0;
        for (int count : counts.values()) {
            largest = Math.max(largest, count);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("components", counts.size());
        output.put("largest", largest);
        return output;
    }

    private int find(int[] parent, int value) {
        int current = value;
        while (parent[current] != current) {
            parent[current] = parent[parent[current]];
            current = parent[current];
        }
        return current;
    }

    private void union(int[] parent, int[] rank, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA == rootB) {
            return;
        }
        if (rank[rootA] < rank[rootB]) {
            int tmp = rootA;
            rootA = rootB;
            rootB = tmp;
        }
        parent[rootB] = rootA;
        if (rank[rootA] == rank[rootB]) {
            rank[rootA]++;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> typedList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static Double numberOrNull(Object value) {
        Number number = number(value);
        if (number == null) {
            return null;
        }
        double converted = number.doubleValue();
        return Double.isFinite(converted) ? converted : null;
    }

    private static double numberOr(Object value, double fallback) {
        Double number = numberOrNull(value);
        return number == null ? fallback : number;
    }

    private static long longOr(Object value, long fallback) {
        Number number = number(value);
        return number == null ? fallback : number.longValue();
    }

    private static String stringOr(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String text = current.getMessage();
            if (text != null && !text.isBlank()) {
                return text;
            }
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }

    private String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        appendJson(builder, value);
        return builder.toString();
    }

    private void appendJson(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            appendString(builder, text);
        } else if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (Double.isFinite(asDouble)) {
                builder.append(number);
            } else {
                builder.append("null");
            }
        } else if (value instanceof Boolean bool) {
            builder.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                appendString(builder, entry.getKey().toString());
                builder.append(':');
                appendJson(builder, entry.getValue());
                first = false;
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                appendJson(builder, item);
                first = false;
            }
            builder.append(']');
        } else {
            appendString(builder, value.toString());
        }
    }

    private void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    private record RunColumns(
            String key,
            String name,
            String description,
            String xColumn,
            String yColumn) {
    }
}
