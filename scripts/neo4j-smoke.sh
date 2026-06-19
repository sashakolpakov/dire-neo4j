#!/usr/bin/env bash
set -euo pipefail

NEO4J_VERSION="${NEO4J_VERSION:-5.26.27}"
NEO4J_IMAGE="${NEO4J_IMAGE:-neo4j:${NEO4J_VERSION}}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-password}"
CONTAINER_NAME="${CONTAINER_NAME:-dire-neo4j-smoke}"
HTTP_PORT="${HTTP_PORT:-7474}"
BOLT_PORT="${BOLT_PORT:-7687}"

usage() {
  cat <<USAGE
Usage: $0 [path-to-plugin-jar]

Build first with mvn package or mvn verify. If no jar path is supplied, the
script uses the single shaded neo4j-plugin/target/dire-neo4j-plugin-*.jar file.
USAGE
}

absolute_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$(pwd)" "$1" ;;
  esac
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required for the Neo4j smoke test" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required for the Neo4j smoke test" >&2
  exit 1
fi

if [[ $# -gt 0 ]]; then
  JAR_PATH="$1"
else
  jars=()
  while IFS= read -r jar; do
    jars+=("$jar")
  done < <(find neo4j-plugin/target -maxdepth 1 -type f -name 'dire-neo4j-plugin-*.jar' ! -name 'original-*' | sort)

  if [[ "${#jars[@]}" -ne 1 ]]; then
    echo "expected exactly one shaded plugin jar, found ${#jars[@]}" >&2
    printf '  %s\n' "${jars[@]}" >&2
    exit 1
  fi
  JAR_PATH="${jars[0]}"
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "plugin jar does not exist: $JAR_PATH" >&2
  exit 1
fi

JAR_PATH="$(absolute_path "$JAR_PATH")"

cleanup() {
  if [[ "${KEEP_CONTAINER:-0}" != "1" ]]; then
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "Starting $NEO4J_IMAGE with $JAR_PATH"
docker run -d \
  --name "$CONTAINER_NAME" \
  -p "$HTTP_PORT:7474" \
  -p "$BOLT_PORT:7687" \
  -v "$JAR_PATH:/plugins/dire-neo4j-plugin.jar:ro" \
  -e "NEO4J_AUTH=${NEO4J_USER}/${NEO4J_PASSWORD}" \
  -e 'NEO4J_dbms_security_procedures_unrestricted=dire.*' \
  -e 'NEO4J_dbms_security_procedures_allowlist=dire.*' \
  -e 'NEO4J_server_unmanaged__extension__classes=org.dire.neo4j.plugin=/dire' \
  "$NEO4J_IMAGE" >/dev/null

cypher() {
  docker exec "$CONTAINER_NAME" cypher-shell \
    -u "$NEO4J_USER" \
    -p "$NEO4J_PASSWORD" \
    --format plain \
    "$@"
}

ready=0
for attempt in $(seq 1 90); do
  if cypher 'RETURN 1 AS ok;' >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" -ne 1 ]]; then
  echo "Neo4j did not become ready" >&2
  docker logs "$CONTAINER_NAME" >&2 || true
  exit 1
fi

procedures="$(cypher "SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'dire.' RETURN name ORDER BY name;")"
for procedure in dire.layout.estimate dire.layout.stats dire.layout.stream dire.layout.write; do
  if ! grep -Fq "$procedure" <<<"$procedures"; then
    echo "missing procedure: $procedure" >&2
    echo "$procedures" >&2
    exit 1
  fi
done

cypher "
MATCH (n:Smoke) DETACH DELETE n;
CREATE (a:Smoke {name: 'a'})
CREATE (b:Smoke {name: 'b'})
CREATE (c:Smoke {name: 'c'})
CREATE (d:Smoke {name: 'd'})
CREATE (a)-[:LINK {weight: 2.0}]->(b)
CREATE (b)-[:LINK]->(c)
CREATE (c)-[:LINK]->(d)
CREATE (d)-[:LINK]->(a);
" >/dev/null

cypher "
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Smoke) RETURN id(n) AS id',
  relationshipQuery: 'MATCH (a:Smoke)-[:LINK]->(b:Smoke) RETURN id(a) AS source, id(b) AS target',
  writeProperties: ['dire_x', 'dire_y'],
  writeInitialProperties: ['dire_initial_x', 'dire_initial_y'],
  iterations: 3,
  randomSeed: 99
})
YIELD nodesWritten, relationshipsRead, iterations
RETURN nodesWritten, relationshipsRead, iterations;
" >/dev/null

layout_count="$(cypher "MATCH (n:Smoke) WHERE n.dire_x IS NOT NULL AND n.dire_y IS NOT NULL RETURN count(n) AS laidOut;")"
if ! grep -Eq '(^|[^0-9])4([^0-9]|$)' <<<"$layout_count"; then
  echo "dire.layout.write did not write coordinates to all smoke nodes" >&2
  echo "$layout_count" >&2
  exit 1
fi

viewer_html="$(curl -fsS -u "${NEO4J_USER}:${NEO4J_PASSWORD}" "http://localhost:${HTTP_PORT}/dire/")"
if ! grep -Fq '<title>DiRe Neo4j</title>' <<<"$viewer_html"; then
  echo "the /dire/ unmanaged viewer did not serve the expected HTML" >&2
  exit 1
fi

viewer_script="$(curl -fsS -u "${NEO4J_USER}:${NEO4J_PASSWORD}" "http://localhost:${HTTP_PORT}/dire/viewer-script")"
if ! grep -Fq 'loadDefault' <<<"$viewer_script"; then
  echo "the /dire/viewer-script endpoint did not serve the expected script" >&2
  exit 1
fi

echo "Neo4j plugin smoke test passed"
