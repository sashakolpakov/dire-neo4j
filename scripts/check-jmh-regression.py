#!/usr/bin/env python3

import json
import math
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: check-jmh-regression.py THRESHOLDS RESULT...", file=sys.stderr)
        return 2

    thresholds = json.loads(Path(sys.argv[1]).read_text())
    scores = {}
    for result_path in sys.argv[2:]:
        for result in json.loads(Path(result_path).read_text()):
            scores[result["benchmark"]] = result["primaryMetric"]["score"]

    failures = []
    for benchmark, maximum in thresholds.items():
        score = scores.get(benchmark)
        if score is None:
            failures.append(f"{benchmark}: missing result")
        elif not math.isfinite(score):
            failures.append(f"{benchmark}: non-finite score {score}")
        elif score > maximum:
            failures.append(f"{benchmark}: {score:.3f} ms/op exceeds {maximum:.3f} ms/op")
        else:
            print(f"{benchmark}: {score:.3f} ms/op <= {maximum:.3f} ms/op")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
