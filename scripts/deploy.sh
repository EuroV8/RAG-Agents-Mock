#!/usr/bin/env bash
set -euo pipefail

check_command(){
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "[ERROR] Required dependency '$1' is not available in PATH" >&2
        exit 1
    fi
}

#env vars
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/opensearch/docker-compose.yml"
OPENSEARCH_URL="${OPENSEARCH_ENDPOINT:-http://localhost:9200}"
MAX_ATTEMPTS=30
SLEEP_SECONDS=2

#dependency check
check_command curl
check_command docker
check_command java
check_command mvn
check_command python

if [ ! -f "${COMPOSE_FILE}" ]; then
    echo "[ERROR] Expected Compose file at ${COMPOSE_FILE}" >&2
    exit 1
fi

#setting docker env vars
if docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose)
elif docker-compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose)
else
    echo "[ERROR] Neither 'docker compose' nor 'docker-compose' is available." >&2
    exit 1
fi

#starting docker daemon if not running
if ! systemctl is-active --quiet docker; then
    echo "[INFO] Docker daemon not running; attempting to start it"
    if sudo systemctl start docker; then
        echo "[INFO] Docker daemon started successfully"
    else
        echo "[ERROR] Failed to start Docker daemon; start it manually and retry" >&2
        exit 1
    fi
fi

#checking docker group membership
if ! groups | grep -q '\bdocker\b'; then
    echo "[WARN] Current user is not in the 'docker' group"
    echo "[INFO] Attempting to add ${USER} to the docker group"
    if sudo usermod -aG docker "$USER"; then
        echo "[INFO] Added ${USER} to docker group. Log out and log back in, then rerun this script for the changes to take effect"
    else
        echo "[ERROR] Failed to add ${USER} to docker group. Add the user manually and retry" >&2
    fi
    exit 1
fi

echo "[INFO] Starting OpenSearch services via Docker Compose"
"${COMPOSE_BIN[@]}" -f "${COMPOSE_FILE}" up -d

echo "[INFO] Waiting for OpenSearch at ${OPENSEARCH_URL}..."
attempt=1
while [ "${attempt}" -le "${MAX_ATTEMPTS}" ]; do
    if curl -s "${OPENSEARCH_URL}/_cluster/health" >/dev/null 2>&1; then
        echo "[INFO] OpenSearch is responsive"
        echo "[INFO] OpenSearch container deployed successfully"
        break
    fi
    if [ "${attempt}" -eq "${MAX_ATTEMPTS}" ]; then
        echo "[WARNING] OpenSearch did not become ready after $((MAX_ATTEMPTS * SLEEP_SECONDS)) seconds." >&2
    fi
    attempt=$((attempt + 1))
    sleep "${SLEEP_SECONDS}"
done

echo "Do you want to set up the local embedding server? Current setting: SKIP_EMBEDDINGS=${SKIP_EMBEDDINGS:-not set} [y/n] "
read -r response
if [[ "$response" =~ ^([nN][oO]|[nN])$ ]]
then
    export SKIP_EMBEDDINGS=true
else
    unset SKIP_EMBEDDINGS
fi

if [ -z "${SKIP_EMBEDDINGS:-}" ]; then
    echo "[INFO] Installing local embedding server Python deps"
    (
        cd "${PROJECT_ROOT}"/embedding_server
        python -m venv .venv
        source .venv/bin/activate
        pip install -r requirements.txt
        python local_embedding_server.py &
        sleep 5
    )
    echo "[INFO] Local embedding server setup complete"
else
    echo "[INFO] SKIP_EMBEDDINGS set; skipping local embedding server setup"
fi

echo "[INFO] Building executable JAR with Maven"
(
    cd "${PROJECT_ROOT}"
    mvn -q -DskipTests package
)

INDEX_DOCS=${INDEX_DOCS:-false}
echo "Do you want to index documentation into OpenSearch? Current setting: INDEX_DOCS=${INDEX_DOCS} [y/n] "
read -r response
if [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]
then
    INDEX_DOCS=true
else
    INDEX_DOCS=false
fi

if [ "${INDEX_DOCS}" = "true" ]; then
    echo "[INFO] Indexing  documentation into OpenSearch"
    (
        cd "${PROJECT_ROOT}"
        mvn -q exec:java -Dexec.mainClass=com.inksoftware.tools.OpenSearchIndexer -Dexec.args="docs/technical"
        mvn -q exec:java -Dexec.mainClass=com.inksoftware.tools.OpenSearchIndexer -Dexec.args="docs/billing"
    )
fi

echo "[INFO] Setup complete"
