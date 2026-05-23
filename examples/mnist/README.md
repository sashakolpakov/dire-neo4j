# MNIST Example

This example loads MNIST as a weighted k-nearest-neighbor graph, writes DiRe
coordinates, and prepares the `/dire/` viewer metadata. The canonical MNIST
files contain 60,000 training images and 10,000 test images.

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

Load a 20,000-image working graph:

```sh
python3 examples/mnist/load_mnist_graph.py \
  --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
  --mnist-dir .tmp/mnist \
  --samples 20000 \
  --layout-runs all
```

Load the full 70,000-image train+test graph:

```sh
python3 examples/mnist/load_mnist_graph.py \
  --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
  --mnist-dir .tmp/mnist \
  --split all \
  --full \
  --neighbor-mode clustered \
  --layout-runs all
```

The loader stores:

- nodes as `(:Paper:MNIST)` with `idx`, `name`, `group`, `digit`, `sourceSplit`, and `sourceIndex`;
- weighted `:CITES` relationships between nearest neighbors;
- `kind: 'local'` for same-digit edges and `kind: 'bridge'` for cross-digit edges;
- `dire_initial_*`, `dire_*`, and `dire_wide_*` coordinates depending on `--layout-runs`.

`--neighbor-mode auto` uses exact kNN up to `--exact-threshold` nodes and
clustered approximate kNN above that. The viewer default loads a 20,000-node
random sample from the stored graph. Edit the first node-query line, for example
`WITH 70000 AS sampleSize`, to request all MNIST nodes; for that size, increase
Neo4j heap if the response is too large for the default server settings.
