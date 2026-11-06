#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 1 ]] || { echo "usage: $0 backup.sql.gz" >&2; exit 2; }
cd "$(dirname "$0")/.."
backup="$1"
sha256sum -c "$backup.sha256"
gzip -dc "$backup" | docker compose -f infra/compose/compose.demo.yml exec -T postgres psql -v ON_ERROR_STOP=1 -U sitepilot sitepilot
