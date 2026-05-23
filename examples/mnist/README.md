# MNIST Example

This example loads MNIST as a weighted k-nearest-neighbor graph, writes DiRe
coordinates, and prepares the `/dire/` viewer metadata.

Download the train and test files:

```sh
python3 -m pip install numpy scikit-learn

mkdir -p .tmp/mnist
curl -L -o .tmp/mnist/train-images-idx3-ubyte.gz \
  https://storage.googleapis.com/cvdf-datasets/mnist/train-images-idx3-ubyte.gz
curl -L -o .tmp/mnist/train-labels-idx1-ubyte.gz \
  https://storage.googleapis.com/cvdf-datasets/mnist/train-labels-idx1-ubyte.gz
curl -L -o .tmp/mnist/t10k-images-idx3-ubyte.gz \
  https://storage.googleapis.com/cvdf-datasets/mnist/t10k-images-idx3-ubyte.gz
curl -L -o .tmp/mnist/t10k-labels-idx1-ubyte.gz \
  https://storage.googleapis.com/cvdf-datasets/mnist/t10k-labels-idx1-ubyte.gz
```

Load a larger working sample:

```sh
python3 examples/mnist/load_mnist_graph.py \
  --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
  --mnist-dir .tmp/mnist \
  --samples 20000 \
  --layout-runs all
```

Load the full 70,000-image train+test set:

```sh
python3 examples/mnist/load_mnist_graph.py \
  --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
  --mnist-dir .tmp/mnist \
  --full \
  --split all \
  --neighbor-mode clustered \
  --layout-runs wide
```

The loader stores:

- nodes as `(:Paper:MNIST)` with `idx`, `name`, `group`, `digit`, `sourceSplit`, and `sourceIndex`;
- weighted `:CITES` relationships between nearest neighbors;
- `kind: 'local'` for same-digit edges and `kind: 'bridge'` for cross-digit edges;
- `dire_initial_*`, `dire_*`, and `dire_wide_*` coordinates depending on `--layout-runs`.

`--neighbor-mode auto` uses exact kNN up to `--exact-threshold` nodes and
clustered approximate kNN above that. The viewer still starts with a manageable
sample query; edit its first Cypher line, for example `WITH 10000 AS sampleSize`,
to draw more loaded nodes.
