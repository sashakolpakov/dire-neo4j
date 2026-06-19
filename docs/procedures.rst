Procedures
==========

``dire.layout.write``
---------------------

Runs a layout and writes coordinates to node properties.

Required config:

* ``nodeQuery``
* ``relationshipQuery``

Common config:

* ``writeProperties``
* ``writeInitialProperties``
* ``writeBatchSize``
* ``iterations``
* ``randomSeed``
* ``concurrency``
* ``spectralTolerance``
* ``relationshipMode``
* ``negativeSamples``
* ``attractionStrength``
* ``repulsionStrength``

``dire.layout.stream``
----------------------

Runs a layout and streams coordinates without writing to Neo4j.

Returned columns include:

* ``nodeId``
* ``elementId`` when the projection uses ``elementId(...)`` inputs
* ``x``, ``y``, optional ``z``
* ``initialX``, ``initialY``, optional ``initialZ``
* ``embedding`` and ``initialEmbedding`` only when ``includeEmbedding: true``

``dire.layout.stats``
---------------------

Runs a layout and returns runtime/quality values without writing node
coordinates.

``dire.layout.estimate``
------------------------

Estimates memory from query counts or explicit counts.

Configuration Reference
-----------------------

.. list-table::
   :header-rows: 1

   * - Option
     - Default
     - Notes
   * - ``initialization``
     - ``spectral``
     - ``spectral``, ``random``, or ``warm_start``
   * - ``relationshipMode``
     - ``undirected``
     - ``directed`` or ``undirected``
   * - ``dimensions``
     - ``2``
     - 2D or 3D coordinates
   * - ``writeProperties``
     - ``['dire_x', 'dire_y']``
     - final coordinates
   * - ``writeInitialProperties``
     - ``['dire_initial_x', 'dire_initial_y']``
     - initialization coordinates
   * - ``writeBatchSize``
     - unset
     - opt-in independent write transactions; earlier batches survive later failures
   * - ``warmStartProperties``
     - ``writeProperties``
     - used with ``initialization: 'warm_start'``
   * - ``negativeSamples``
     - ``16``
     - sampled repulsion per node
   * - ``concurrency``
     - ``min(availableProcessors, 8)``
     - worker threads
   * - ``includeEmbedding``
     - ``false``
     - include boxed ``embedding`` and ``initialEmbedding`` lists in stream results
   * - ``fastKernel``
     - ``false``
     - opt-in shortcut for near-linear kernels; may slightly perturb coordinates
   * - ``spectralTolerance``
     - ``0.0``
     - opt-in normalized subspace convergence threshold; zero keeps fixed iterations
   * - ``spectralMinIterations``
     - ``8``
     - minimum spectral power iterations before convergence can stop
   * - ``spectralMaxIterations``
     - ``160``
     - spectral power-iteration cap
   * - ``maxProjectionBytes``
     - unset
     - optional fail-fast projection memory cap
   * - ``attractionStrength``
     - ``1.0``
     - relationship attraction multiplier
   * - ``repulsionStrength``
     - ``1.0``
     - sampled repulsion multiplier
   * - ``spread``
     - ``1.0``
     - layout spread parameter
   * - ``learningRate``
     - ``1.0``
     - force update scale
   * - ``cutoff``
     - ``42.0``
     - force cutoff

Projection Rules
----------------

* ``nodeQuery`` must return numeric ``id`` values or string ``elementId(...)``
  values as ``id``.
* ``relationshipQuery`` must return matching numeric or string ``source`` and
  ``target`` values.
* Optional ``weight`` values must be finite and non-negative.
* Zero-weight relationships are ignored.
* Relationships whose endpoints are not present in ``nodeQuery`` cannot be used.

Write Transaction Semantics
---------------------------

Without ``writeBatchSize``, coordinate properties participate in the caller's
transaction and roll back with it. A positive ``writeBatchSize`` commits
independent transactions after projection and layout. This bounds transaction
size but is not atomic, and batch transactions cannot see uncommitted caller
changes.
