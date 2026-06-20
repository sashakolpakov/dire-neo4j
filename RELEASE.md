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
2. Confirm the `neo4j-5.26` and `neo4j-2026.05` profile versions in `pom.xml`.
3. Run `mvn -B -ntp -Pneo4j-5.26 verify`.
4. Run the plugin tests and smoke test against both supported profiles.
5. Update user-facing docs if the install command or supported Neo4j version changed.

## Tag And Publish

Create and push a tag that matches the Maven project version:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The release workflow refuses to publish a `*-SNAPSHOT` version and refuses tags
that do not match the Maven project version. It then:

1. Builds the `neo4j-5.26` and `neo4j-2026.05` profiles.
2. Starts a pinned Docker container for each supported Neo4j version.
3. Verifies the `dire.layout.*` procedures.
4. Calls `dire.layout.write` on a smoke graph.
5. Verifies the `/dire/` unmanaged viewer endpoints.
6. Publishes both versioned jars and their SHA-256 checksums.

## GitHub Actions Permissions

No custom release secret is required. The workflow uses the repository
`GITHUB_TOKEN` with `contents: write` permission to create or update the GitHub
Release. The runner must be able to start Docker containers and pull the pinned
Neo4j image used by `scripts/neo4j-smoke.sh`.

The separate fast-kernel benchmark suite is intentionally manual-only. It lives
behind `.github/workflows/fast-kernel-benchmarks.yml` and the local helper
`scripts/run-fast-kernel-benchmarks.sh`; it is not part of normal CI or the
release workflow.

## Compatibility Note

The release jar is for self-managed Neo4j servers that allow custom plugins.
Managed services such as Aura do not support installing arbitrary server plugin
jars.

Supported release profiles are Neo4j 5.26.27 and 2026.05.0. Use the artifact
whose Neo4j version matches the server line exactly.

`fastKernel` is an opt-in dyadic exponent approximation path for the force
kernel. It defaults to `false`; enabling it may slightly perturb coordinates.

## Breaking Changes To Call Out

* The unmanaged viewer no longer runs custom Cypher from `POST /api/query`.
* `dire.layout.stream` no longer allocates or returns `embedding` and
  `initialEmbedding` lists by default. Set `includeEmbedding: true` to retain
  those list-valued columns; scalar coordinate columns are unchanged.
