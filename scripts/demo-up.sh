#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
env_args=()
[[ -f .env ]] && env_args=(--env-file .env)
docker compose "${env_args[@]}" -f infra/compose/compose.demo.yml up -d --build --wait
printf 'Admin console: http://localhost:%s\n' "${SITEPILOT_CONTROL_PORT:-18080}"
