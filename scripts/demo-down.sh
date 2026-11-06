#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
env_args=()
[[ -f .env ]] && env_args=(--env-file .env)
docker compose "${env_args[@]}" -f infra/compose/compose.demo.yml down --remove-orphans
