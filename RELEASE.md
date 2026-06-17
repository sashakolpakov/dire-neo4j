# Release Checklist

This project ships as a Neo4j server plugin jar. Release artifacts are attached
to GitHub Releases and named with the plugin and Neo4j API versions:

```text
dire-neo4j-plugin-<plugin-version>-neo4j-<neo4j-version>.jar
```

Only publish the shaded plugin jar from `neo4j-plugin/target`. Do not publish
`original-*.jar`.

## Before Tagging

1. Update the Maven project version from `*-SNAPSHOT` to the release version.
2. Confirm `neo4j.version` in `pom.xml` is the intended supported Neo4j line.
3. Run `mvn -B -ntp verify`.
4. Run `scripts/neo4j-smoke.sh` against the packaged jar.
5. Update user-facing docs if the install command or supported Neo4j version changed.

## Tag And Publish

Create and push a tag that matches the Maven project version:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The release workflow refuses to publish a `*-SNAPSHOT` version and refuses tags
that do not match the Maven project version. It then:

1. Runs `mvn -B -ntp verify`.
2. Starts a pinned Neo4j Docker container.
3. Verifies the `dire.layout.*` procedures.
4. Calls `dire.layout.write` on a smoke graph.
5. Verifies the `/dire/` unmanaged viewer endpoints.
6. Publishes the renamed jar and its SHA-256 checksum to the GitHub release.

## GitHub Actions Permissions

No custom release secret is required. The workflow uses the repository
`GITHUB_TOKEN` with `contents: write` permission to create or update the GitHub
Release. The runner must be able to start Docker containers and pull the pinned
Neo4j image used by `scripts/neo4j-smoke.sh`.

## Compatibility Note

The release jar is for self-managed Neo4j servers that allow custom plugins.
Managed services such as Aura do not support installing arbitrary server plugin
jars.

`fastKernel` is an opt-in performance shortcut for fitted kernels whose
exponent is close to `1.0`. It defaults to `false`; enabling it may slightly
perturb coordinates.

## Breaking Changes To Call Out

* The unmanaged viewer no longer runs custom Cypher from `POST /api/query`.
* `dire.layout.stream` no longer allocates or returns `embedding` and
  `initialEmbedding` lists by default. Set `includeEmbedding: true` to retain
  those list-valued columns; scalar coordinate columns are unchanged.
