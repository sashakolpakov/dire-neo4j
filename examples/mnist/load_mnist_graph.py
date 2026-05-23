#!/usr/bin/env python3
"""Load MNIST as a k-nearest-neighbor graph and write DiRe layouts."""

from __future__ import annotations

import argparse
import gzip
import json
import math
import os
import struct
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path
from typing import Callable

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sklearn.decomposition import PCA
from sklearn.neighbors import NearestNeighbors


DEFAULT_ENDPOINT = os.environ.get(
    "NEO4J_HTTP",
    "http://127.0.0.1:7474/db/neo4j/tx/commit",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load MNIST into Neo4j as (:Paper:MNIST)-[:CITES]->(:Paper:MNIST).",
    )
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="Neo4j HTTP transaction endpoint.")
    parser.add_argument("--mnist-dir", default=".tmp/mnist", help="Directory containing MNIST gzip files.")
    parser.add_argument(
        "--split",
        choices=["train", "test", "all"],
        default="all",
        help="MNIST split to load. all is the 70,000-image train+test set.",
    )
    parser.add_argument("--samples", type=int, default=10000, help="Total MNIST images to load.")
    parser.add_argument("--per-digit", type=int, default=None, help="Load this many images per digit.")
    parser.add_argument("--full", action="store_true", help="Load every image from the selected split.")
    parser.add_argument("--k", type=int, default=14, help="Nearest neighbors per image.")
    parser.add_argument("--seed", type=int, default=42, help="Random seed.")
    parser.add_argument("--pca-components", type=int, default=50, help="PCA dimensions for kNN.")
    parser.add_argument(
        "--neighbor-mode",
        choices=["auto", "exact", "clustered"],
        default="auto",
        help="kNN construction mode. auto uses clustered mode for large loads.",
    )
    parser.add_argument(
        "--exact-threshold",
        type=int,
        default=12000,
        help="Largest auto-mode sample that uses exact kNN.",
    )
    parser.add_argument("--cluster-size", type=int, default=2000, help="Target bucket size for clustered kNN.")
    parser.add_argument(
        "--candidate-limit",
        type=int,
        default=6000,
        help="Approximate candidate pool per clustered kNN bucket.",
    )
    parser.add_argument(
        "--neighbor-clusters",
        type=int,
        default=2,
        help="Minimum neighboring centroid buckets to include in clustered kNN.",
    )
    parser.add_argument(
        "--distance-batch",
        type=int,
        default=256,
        help="Rows per matrix-multiply distance batch in clustered kNN.",
    )
    parser.add_argument(
        "--bridge-weight-scale",
        type=float,
        default=0.35,
        help="Multiplier for cross-label kNN edge weights.",
    )
    parser.add_argument(
        "--layout-runs",
        choices=["all", "wide", "none"],
        default="all",
        help="DiRe coordinate sets to write after loading the graph.",
    )
    parser.add_argument("--concurrency", type=int, default=8, help="DiRe worker threads.")
    parser.add_argument("--node-batch", type=int, default=1000, help="Neo4j node write batch size.")
    parser.add_argument("--edge-batch", type=int, default=2500, help="Neo4j relationship write batch size.")
    parser.add_argument("--keep-existing", action="store_true", help="Do not clear the database first.")
    return parser.parse_args()


def read_idx_images(path: Path) -> np.ndarray:
    with gzip.open(path, "rb") as handle:
        magic, count, rows, cols = struct.unpack(">IIII", handle.read(16))
        if magic != 2051:
            raise ValueError(f"bad image magic in {path}: {magic}")
        data = np.frombuffer(handle.read(), dtype=np.uint8)
    return data.reshape(count, rows * cols)


def read_idx_labels(path: Path) -> np.ndarray:
    with gzip.open(path, "rb") as handle:
        magic, count = struct.unpack(">II", handle.read(8))
        if magic != 2049:
            raise ValueError(f"bad label magic in {path}: {magic}")
        data = np.frombuffer(handle.read(), dtype=np.uint8)
    if data.shape[0] != count:
        raise ValueError("label count mismatch")
    return data


def read_mnist(root: Path, split: str) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    split_files = {
        "train": ("train-images-idx3-ubyte.gz", "train-labels-idx1-ubyte.gz"),
        "test": ("t10k-images-idx3-ubyte.gz", "t10k-labels-idx1-ubyte.gz"),
    }
    split_names = ["train", "test"] if split == "all" else [split]
    images = []
    labels = []
    source_splits = []
    source_indices = []

    for split_name in split_names:
        image_file, label_file = split_files[split_name]
        image_path = root / image_file
        label_path = root / label_file
        if not image_path.exists() or not label_path.exists():
            raise FileNotFoundError(
                f"missing MNIST {split_name} files under {root}; "
                f"expected {image_file} and {label_file}"
            )
        split_images = read_idx_images(image_path)
        split_labels = read_idx_labels(label_path)
        if split_images.shape[0] != split_labels.shape[0]:
            raise ValueError(f"image/label count mismatch for {split_name}")
        images.append(split_images)
        labels.append(split_labels)
        source_splits.extend([split_name] * split_labels.shape[0])
        source_indices.extend(range(split_labels.shape[0]))

    return (
        np.vstack(images),
        np.concatenate(labels),
        np.array(source_splits),
        np.array(source_indices, dtype=np.int64),
    )


def post(endpoint: str, statement: str, parameters: dict | None = None, timeout: int = 3600) -> list[dict]:
    payload = json.dumps(
        {
            "statements": [
                {
                    "statement": statement,
                    "parameters": parameters or {},
                }
            ]
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    if body.get("errors"):
        raise RuntimeError(json.dumps(body["errors"], indent=2))

    rows = []
    for block in body.get("results", []):
        columns = block.get("columns", [])
        for item in block.get("data", []):
            rows.append(dict(zip(columns, item.get("row", []))))
    return rows


def select_indices(labels: np.ndarray, args: argparse.Namespace) -> np.ndarray:
    rng = np.random.default_rng(args.seed)
    if args.full:
        selected = np.arange(labels.shape[0], dtype=np.int64)
        rng.shuffle(selected)
        return selected

    if args.per_digit is not None:
        counts = {digit: args.per_digit for digit in range(10)}
    else:
        if args.samples < 10:
            raise ValueError("--samples must be at least 10 unless --full is used")
        base = args.samples // 10
        counts = {digit: base for digit in range(10)}
        for digit in range(args.samples - base * 10):
            counts[digit] += 1

    selected: list[int] = []
    for digit, count in counts.items():
        candidates = np.flatnonzero(labels == digit)
        if count > candidates.shape[0]:
            raise ValueError(f"not enough examples for digit {digit}: requested {count}")
        selected.extend(rng.choice(candidates, size=count, replace=False).tolist())
    selected_array = np.array(selected, dtype=np.int64)
    rng.shuffle(selected_array)
    return selected_array


def embed_images(images: np.ndarray, args: argparse.Namespace) -> tuple[np.ndarray, np.ndarray, float]:
    components = min(args.pca_components, images.shape[0] - 1, images.shape[1])
    if components < 2:
        raise ValueError("need at least two PCA components")

    x = images.astype(np.float32) / 255.0
    pca = PCA(n_components=components, svd_solver="randomized", random_state=args.seed)
    z = pca.fit_transform(x).astype(np.float32)
    variance = float(np.sum(pca.explained_variance_ratio_))
    return z, z[:, :2].copy(), variance


def exact_neighbor_pairs(z: np.ndarray, k: int) -> tuple[list[tuple[int, int, float]], list[float]]:
    neighbors = NearestNeighbors(n_neighbors=k + 1, metric="euclidean", n_jobs=-1)
    neighbors.fit(z)
    distances, indices = neighbors.kneighbors(z)

    pairs: list[tuple[int, int, float]] = []
    kth_distances: list[float] = []
    for source in range(z.shape[0]):
        kth_distances.append(float(distances[source, -1]))
        for distance, target in zip(distances[source, 1:], indices[source, 1:]):
            pairs.append((source, int(target), float(distance)))
    return pairs, kth_distances


def clustered_neighbor_pairs(
    z: np.ndarray,
    k: int,
    args: argparse.Namespace,
) -> tuple[list[tuple[int, int, float]], list[float]]:
    node_count = z.shape[0]
    cluster_count = max(2, min(node_count, math.ceil(node_count / max(1, args.cluster_size))))
    print(f"Building clustered kNN with {cluster_count} buckets.", flush=True)

    kmeans = MiniBatchKMeans(
        n_clusters=cluster_count,
        random_state=args.seed,
        batch_size=min(8192, max(1024, node_count // 4)),
        n_init=5,
        max_iter=120,
    )
    cluster_labels = kmeans.fit_predict(z)
    centers = kmeans.cluster_centers_.astype(np.float32)

    members_by_cluster = [np.flatnonzero(cluster_labels == cluster_id) for cluster_id in range(cluster_count)]
    center_norms = np.einsum("ij,ij->i", centers, centers)
    center_d2 = center_norms[:, None] + center_norms[None, :] - 2.0 * centers @ centers.T
    center_order = np.argsort(np.maximum(center_d2, 0.0), axis=1)
    z_norms = np.einsum("ij,ij->i", z, z)

    pairs: list[tuple[int, int, float]] = []
    kth_distances: list[float] = []
    target_candidates = min(node_count, max(args.candidate_limit, k + 1))

    started = time.time()
    for cluster_id in range(cluster_count):
        members = members_by_cluster[cluster_id]
        if members.size == 0:
            continue

        candidate_parts = []
        candidate_count = 0
        for offset, other_cluster in enumerate(center_order[cluster_id]):
            part = members_by_cluster[int(other_cluster)]
            if part.size == 0:
                continue
            candidate_parts.append(part)
            candidate_count += int(part.size)
            enough_clusters = offset >= args.neighbor_clusters
            if enough_clusters and candidate_count >= target_candidates:
                break

        candidates = np.unique(np.concatenate(candidate_parts)).astype(np.int64)
        take = min(k, candidates.shape[0] - 1)
        if take < 1:
            continue

        candidate_vectors = z[candidates]
        candidate_norms = z_norms[candidates]
        for start in range(0, members.shape[0], args.distance_batch):
            batch = members[start : start + args.distance_batch]
            distances2 = (
                z_norms[batch, None]
                + candidate_norms[None, :]
                - 2.0 * z[batch] @ candidate_vectors.T
            )
            np.maximum(distances2, 0.0, out=distances2)
            distances2[candidates[None, :] == batch[:, None]] = np.inf
            nearest = np.argpartition(distances2, kth=take - 1, axis=1)[:, :take]
            for row, source in enumerate(batch):
                order = np.argsort(distances2[row, nearest[row]])
                ordered_positions = nearest[row, order]
                row_distances = np.sqrt(distances2[row, ordered_positions])
                kth_distances.append(float(row_distances[-1]))
                for position, distance in zip(ordered_positions, row_distances):
                    pairs.append((int(source), int(candidates[position]), float(distance)))

        if cluster_id == 0 or (cluster_id + 1) % 5 == 0 or cluster_id + 1 == cluster_count:
            elapsed = time.time() - started
            print(f"  bucket {cluster_id + 1}/{cluster_count} in {elapsed:.1f}s", flush=True)

    return pairs, kth_distances


def build_edges(
    labels: np.ndarray,
    pairs: list[tuple[int, int, float]],
    kth_distances: list[float],
    args: argparse.Namespace,
) -> list[dict]:
    sigma = float(np.median(kth_distances)) if kth_distances else 1.0
    sigma = max(sigma, 1.0e-6)
    edge_by_pair: dict[tuple[int, int], dict] = {}

    for source, target, distance in pairs:
        if source == target:
            continue
        a, b = sorted((int(source), int(target)))
        bridge = int(labels[source]) != int(labels[target])
        weight = float(np.exp(-(distance * distance) / (2.0 * sigma * sigma)))
        if bridge:
            weight *= args.bridge_weight_scale
        row = {
            "source": a,
            "target": b,
            "weight": max(weight, 1.0e-9),
            "kind": "bridge" if bridge else "local",
            "distance": float(distance),
        }
        existing = edge_by_pair.get((a, b))
        if existing is None or row["weight"] > existing["weight"]:
            edge_by_pair[(a, b)] = row

    return list(edge_by_pair.values())


def components(node_count: int, edges: list[dict]) -> tuple[list[list[int]], Callable[[int, int], None]]:
    parent = list(range(node_count))
    rank = [0] * node_count

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        root_a, root_b = find(a), find(b)
        if root_a == root_b:
            return
        if rank[root_a] < rank[root_b]:
            root_a, root_b = root_b, root_a
        parent[root_b] = root_a
        if rank[root_a] == rank[root_b]:
            rank[root_a] += 1

    for edge in edges:
        union(int(edge["source"]), int(edge["target"]))

    groups: dict[int, list[int]] = defaultdict(list)
    for idx in range(node_count):
        groups[find(idx)].append(idx)
    return list(groups.values()), union


def connect_components(z: np.ndarray, edges: list[dict]) -> None:
    groups, union = components(z.shape[0], edges)
    if len(groups) <= 1:
        return

    print(f"Connecting {len(groups)} kNN components with weak bridge edges.", flush=True)
    centroids = np.array([z[group].mean(axis=0) for group in groups], dtype=np.float32)
    remaining = set(range(len(groups)))
    connected = {remaining.pop()}
    while remaining:
        best = None
        remaining_list = list(remaining)
        for comp_a in connected:
            delta = centroids[remaining_list] - centroids[comp_a]
            dists = np.einsum("ij,ij->i", delta, delta)
            offset = int(np.argmin(dists))
            comp_b = remaining_list[offset]
            distance = float(np.sqrt(dists[offset]))
            if best is None or distance < best[0]:
                best = (distance, comp_a, comp_b)
        _, comp_a, comp_b = best
        ia = int(groups[comp_a][0])
        ib = int(groups[comp_b][0])
        edges.append(
            {
                "source": min(ia, ib),
                "target": max(ia, ib),
                "weight": 0.03,
                "kind": "bridge",
                "distance": float(np.linalg.norm(z[ia] - z[ib])),
            }
        )
        union(ia, ib)
        connected.add(comp_b)
        remaining.remove(comp_b)


def make_nodes(
    source_indices: np.ndarray,
    source_splits: np.ndarray,
    labels: np.ndarray,
    pca2: np.ndarray,
) -> list[dict]:
    nodes = []
    for idx, source_index in enumerate(source_indices.tolist()):
        digit = int(labels[idx])
        source_split = str(source_splits[idx])
        nodes.append(
            {
                "idx": idx,
                "name": f"{digit}:{idx}",
                "group": str(digit),
                "digit": digit,
                "sourceIndex": int(source_index),
                "sourceSplit": source_split,
                "mnistId": f"{source_split}:{int(source_index)}",
                "pca_x": float(pca2[idx, 0]),
                "pca_y": float(pca2[idx, 1]),
            }
        )
    return nodes


def batches(items: list, size: int):
    for start in range(0, len(items), size):
        yield items[start : start + size]


def load_graph(endpoint: str, nodes: list[dict], edges: list[dict], args: argparse.Namespace) -> None:
    if not args.keep_existing:
        print("Clearing Neo4j.", flush=True)
        post(endpoint, "MATCH (n) DETACH DELETE n")

    post(endpoint, "CREATE INDEX mnist_idx IF NOT EXISTS FOR (p:Paper) ON (p.idx)")
    post(endpoint, "CREATE INDEX mnist_group IF NOT EXISTS FOR (p:Paper) ON (p.group)")
    post(endpoint, "CALL db.awaitIndexes()")

    node_statement = """
    UNWIND $rows AS row
    CREATE (p:Paper:MNIST {
      idx: row.idx,
      name: row.name,
      group: row.group,
      digit: row.digit,
      sourceIndex: row.sourceIndex,
      sourceSplit: row.sourceSplit,
      mnistId: row.mnistId,
      pca_x: row.pca_x,
      pca_y: row.pca_y
    })
    """
    print(f"Writing {len(nodes):,} nodes.", flush=True)
    for batch in batches(nodes, args.node_batch):
        post(endpoint, node_statement, {"rows": batch})

    edge_statement = """
    UNWIND $rows AS row
    MATCH (a:Paper {idx: row.source})
    MATCH (b:Paper {idx: row.target})
    CREATE (a)-[:CITES {
      weight: row.weight,
      kind: row.kind,
      distance: row.distance
    }]->(b)
    """
    print(f"Writing {len(edges):,} relationships.", flush=True)
    for batch in batches(edges, args.edge_batch):
        post(endpoint, edge_statement, {"rows": batch})


def layout_runs(args: argparse.Namespace) -> list[dict]:
    relationship_query = """
      MATCH (a:Paper)-[r:CITES]->(b:Paper)
      RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
    """
    node_query = "MATCH (n:Paper) RETURN id(n) AS id"
    initial = {
        "key": "dire_initial",
        "name": "Initial",
        "description": "Spectral initialization before DiRe refinement",
        "rank": 0,
        "config": {
            "nodeQuery": node_query,
            "relationshipQuery": relationship_query,
            "writeProperties": ["dire_initial_x", "dire_initial_y"],
            "writeInitialProperties": [],
            "iterations": 0,
            "randomSeed": args.seed,
            "negativeSamples": 0,
            "concurrency": args.concurrency,
        },
    }
    standard = {
        "key": "dire",
        "name": "DiRe",
        "description": "Full-graph DiRe layout",
        "rank": 2,
        "config": {
            "nodeQuery": node_query,
            "relationshipQuery": relationship_query,
            "writeProperties": ["dire_x", "dire_y"],
            "writeInitialProperties": [],
            "initialization": "warm_start",
            "warmStartProperties": ["dire_initial_x", "dire_initial_y"],
            "iterations": 220,
            "randomSeed": args.seed,
            "negativeSamples": 32,
            "attractionStrength": 0.9,
            "repulsionStrength": 1.4,
            "concurrency": args.concurrency,
        },
    }
    fast = {
        "key": "dire_fast",
        "name": "DiRe fast",
        "description": "Short DiRe run",
        "rank": 1,
        "config": {
            "nodeQuery": node_query,
            "relationshipQuery": relationship_query,
            "writeProperties": ["dire_fast_x", "dire_fast_y"],
            "writeInitialProperties": [],
            "initialization": "warm_start",
            "warmStartProperties": ["dire_initial_x", "dire_initial_y"],
            "iterations": 80,
            "randomSeed": args.seed,
            "negativeSamples": 16,
            "repulsionStrength": 1.0,
            "concurrency": args.concurrency,
        },
    }
    wide = {
        "key": "dire_wide",
        "name": "DiRe wide",
        "description": "Higher-separation DiRe layout",
        "rank": 3,
        "config": {
            "nodeQuery": node_query,
            "relationshipQuery": relationship_query,
            "writeProperties": ["dire_wide_x", "dire_wide_y"],
            "writeInitialProperties": [],
            "initialization": "warm_start",
            "warmStartProperties": ["dire_initial_x", "dire_initial_y"],
            "iterations": 360,
            "randomSeed": args.seed + 35,
            "negativeSamples": 64,
            "attractionStrength": 0.75,
            "repulsionStrength": 2.8,
            "spread": 1.8,
            "concurrency": args.concurrency,
        },
    }
    if args.layout_runs == "wide":
        return [initial, wide]
    return [initial, fast, standard, wide]


def write_layouts(endpoint: str, args: argparse.Namespace) -> None:
    if args.layout_runs == "none":
        return

    post(endpoint, "MATCH (r:EmbeddingRun) DETACH DELETE r")
    for run in layout_runs(args):
        print(f"Writing {run['key']}.", flush=True)
        rows = post(
            endpoint,
            """
            CALL dire.layout.write($config)
            YIELD nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength
            RETURN nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength
            """,
            {"config": run["config"]},
        )
        result = rows[0]
        post(
            endpoint,
            """
            CREATE (:EmbeddingRun {
              key: $key,
              xProperty: $xProperty,
              yProperty: $yProperty,
              name: $name,
              description: $description,
              rank: $rank,
              stress: $stress,
              meanEdgeLength: $meanEdgeLength,
              nodesWritten: $nodesWritten,
              relationshipsRead: $relationshipsRead,
              iterations: $iterations,
              milliseconds: $milliseconds
            })
            """,
            {
                "key": run["key"],
                "xProperty": run["config"]["writeProperties"][0],
                "yProperty": run["config"]["writeProperties"][1],
                "name": run["name"],
                "description": run["description"],
                "rank": run["rank"],
                **result,
            },
        )
        print(json.dumps({"run": run["key"], **result}, indent=2), flush=True)

    active = "dire_wide" if args.layout_runs in {"all", "wide"} else "dire"
    post(endpoint, "MATCH (v:DireView) DETACH DELETE v")
    post(
        endpoint,
        """
        CREATE (:DireView {
          name: 'Current Browser View',
          run: $run,
          description: 'MNIST kNN graph'
        })
        """,
        {"run": active},
    )


def summarize(endpoint: str) -> None:
    groups = post(
        endpoint,
        """
        MATCH (p:Paper)
        WITH p.group AS digit, count(*) AS nodes
        ORDER BY digit
        RETURN collect({digit: digit, nodes: nodes}) AS groups
        """,
    )
    edges = post(
        endpoint,
        """
        MATCH ()-[r:CITES]->()
        RETURN count(*) AS edges,
               sum(CASE coalesce(r.kind, '') WHEN 'local' THEN 1 ELSE 0 END) AS localEdges,
               sum(CASE coalesce(r.kind, '') WHEN 'bridge' THEN 1 ELSE 0 END) AS bridgeEdges
        """,
    )
    print(json.dumps(groups[0], indent=2), flush=True)
    print(json.dumps(edges[0], indent=2), flush=True)


def main() -> int:
    args = parse_args()
    root = Path(args.mnist_dir)
    images, labels, source_splits, source_indices = read_mnist(root, args.split)
    selected = select_indices(labels, args)
    selected_labels = labels[selected].astype(int)
    selected_images = images[selected]
    selected_source_splits = source_splits[selected]
    selected_source_indices = source_indices[selected]

    print(f"Selected {selected.shape[0]:,} MNIST images from split={args.split}.", flush=True)
    print(f"digits={dict(sorted(Counter(selected_labels.tolist()).items()))}", flush=True)

    z, pca2, variance = embed_images(selected_images, args)
    mode = args.neighbor_mode
    if mode == "auto":
        mode = "exact" if selected.shape[0] <= args.exact_threshold else "clustered"
    print(f"PCA dimensions={z.shape[1]} variance={variance:.4f}; neighbor mode={mode}.", flush=True)

    if mode == "exact":
        pairs, kth_distances = exact_neighbor_pairs(z, args.k)
    else:
        pairs, kth_distances = clustered_neighbor_pairs(z, args.k, args)

    edges = build_edges(selected_labels, pairs, kth_distances, args)
    connect_components(z, edges)
    nodes = make_nodes(selected_source_indices, selected_source_splits, selected_labels, pca2)

    print(
        f"MNIST graph nodes={len(nodes):,} edges={len(edges):,} "
        f"k={args.k} bridges={sum(1 for edge in edges if edge['kind'] == 'bridge'):,}",
        flush=True,
    )
    load_graph(args.endpoint, nodes, edges, args)
    write_layouts(args.endpoint, args)
    summarize(args.endpoint)
    print("done", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
