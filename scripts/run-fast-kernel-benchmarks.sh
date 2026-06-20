#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-benchmarks/fast-kernel-output}"

mkdir -p "$OUTPUT_DIR"

java -cp benchmarks/target/benchmarks.jar org.dire.neo4j.core.FastKernelMatrix \
  > "$OUTPUT_DIR/fast-kernel-matrix.csv"

run_jmh_slice() {
  local min_dist="$1"
  local spread="$2"
  local label="$3"
  java -jar benchmarks/target/benchmarks.jar \
    CoreLayoutBenchmark.randomInitLayout \
    -wi 0 -i 1 -r 3s -f 1 \
    -p nodeCount=10000 \
    -p longRangeEdgesPerNode=1 \
    -p iterations=10 \
    -p concurrency=1 \
    -p minDist="$min_dist" \
    -p spread="$spread" \
    -p fastKernel=false,true \
    > "$OUTPUT_DIR/$label.txt"
}

run_jmh_slice 0.01 1.0 jmh-minDist-0.01-spread-1.0
run_jmh_slice 0.2 1.0 jmh-minDist-0.2-spread-1.0
run_jmh_slice 1.0 0.5 jmh-minDist-1.0-spread-0.5
