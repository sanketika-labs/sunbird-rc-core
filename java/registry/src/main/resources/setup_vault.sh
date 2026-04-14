#!/bin/bash

# This script does the following:
# * Waits for vault to respond on localhost:8200
# * Initializes vault on first run: saves keys to keys.txt, updates VAULT_TOKEN in .env
# * On restart: uses existing keys.txt to unseal (skips init)
# * Enables kv-v2 secrets engine on first run

COMPOSE_FILE="${1:-docker-compose.yml}"
SERVICE_NAME="${2:-vault}"
VAULT_PORT="${3:-8200}"

echo "Setting up $SERVICE_NAME in $COMPOSE_FILE"

# Wait for vault HTTP API to respond
echo "Waiting for Vault to start..."
until curl -s "http://localhost:${VAULT_PORT}/v1/sys/health" 2>/dev/null | grep -q '"initialized"'; do
    echo "Vault not yet reachable, retrying..."
    sleep 2
done
echo "Vault is reachable."

# Get vault status from HTTP API (JSON response)
vault_health=$(curl -s "http://localhost:${VAULT_PORT}/v1/sys/health" 2>/dev/null)

# Get container ID for docker exec
VAULT_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q "$SERVICE_NAME" | head -1)

is_fresh_install=false

if echo "$vault_health" | grep -q '"initialized":true'; then
    echo "Vault is already initialized."
    if [ ! -s "keys.txt" ]; then
        echo "CRITICAL ERROR: Vault is initialized but keys.txt is missing!"
        echo "You must manually provide unseal keys. Cannot proceed."
        exit 1
    fi
    echo "Using existing keys.txt for unsealing."
else
    echo "Vault is not initialized. Initializing..."
    docker exec "$VAULT_CONTAINER" vault operator init > ansi-keys.txt
    sed 's/\x1B\[[0-9;]*[JKmsu]//g' < ansi-keys.txt > keys.txt
    rm -f ansi-keys.txt
    is_fresh_install=true

    # Extract root token and update .env (portable sed: works on macOS and Linux)
    root_token=$(sed -n 's/Initial Root Token: \(.*\)/\1/p' keys.txt | tr -dc '[:print:]')
    sed "s/VAULT_TOKEN=.*/VAULT_TOKEN=$root_token/" ".env" > ".env.tmp" && mv ".env.tmp" ".env"
    echo "Vault initialized. VAULT_TOKEN updated in .env"
fi

# Unseal vault with keys 1, 2, 3 (threshold = 3 of 5)
echo "Unsealing vault..."
key1=$(sed -n 's/Unseal Key 1: \(.*\)/\1/p' keys.txt | tr -dc '[:print:]')
key2=$(sed -n 's/Unseal Key 2: \(.*\)/\1/p' keys.txt | tr -dc '[:print:]')
key3=$(sed -n 's/Unseal Key 3: \(.*\)/\1/p' keys.txt | tr -dc '[:print:]')

docker exec "$VAULT_CONTAINER" vault operator unseal "$key1"
docker exec "$VAULT_CONTAINER" vault operator unseal "$key2"
docker exec "$VAULT_CONTAINER" vault operator unseal "$key3"
echo "Vault unsealed successfully."

# Enable kv-v2 secrets engine on fresh install only
if $is_fresh_install; then
    root_token=$(sed -n 's/Initial Root Token: \(.*\)/\1/p' keys.txt | tr -dc '[:print:]')
    docker exec -e VAULT_TOKEN="$root_token" "$VAULT_CONTAINER" vault secrets enable -path=kv kv-v2
    echo "KV v2 secrets engine enabled."
    echo ""
    echo "Next steps:"
    echo "  docker compose -f $COMPOSE_FILE up -d identity credential-schema credential"
fi

echo ""
echo "NOTE: KEYS ARE STORED IN keys.txt — keep this file safe and NEVER commit it to git!"
