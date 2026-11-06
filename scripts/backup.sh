#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-backups/sitepilot-$(date -u +%Y%m%dT%H%M%SZ).sql.gz}"
mkdir -p "$(dirname "$out")"
docker compose -f infra/compose/compose.demo.yml exec -T postgres pg_dump -U sitepilot sitepilot | gzip -9 > "$out"
sha256sum "$out" > "$out.sha256"
echo "$out"
