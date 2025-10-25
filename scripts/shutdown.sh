#!/bin/bash

#Script to shutdown the python embedding server & opensearch docker containers

echo "[INFO] Stopping local embedding server"
pkill -f local_embedding_server.py
echo "[INFO] Local embedding server stopped"

echo "[INFO] Stopping OpenSearch services via Docker Compose"

docker-compose -f ../opensearch/docker-compose.yml down

echo "[INFO] Shutdown complete"