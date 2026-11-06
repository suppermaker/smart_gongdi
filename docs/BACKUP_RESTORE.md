# Backup and Restore

`./scripts/backup.sh [path.sql.gz]` runs a consistent PostgreSQL logical dump inside the demo container and writes a SHA-256 sidecar. Store both files outside the Docker host and protect them as sensitive business data.

Restore into an already running, empty compatible database with `./scripts/restore.sh path.sql.gz`. The script verifies the sidecar before piping SQL to `psql` with `ON_ERROR_STOP`. For a full replacement, stop application writers, create a fresh database/volume, restore, start the control plane so Flyway validates schema history, then verify risk counts, pending approvals, audit chronology, and a read-only UI flow.

Temporal history is in a separate volume and is not included in the business database dump. Production backup policy must separately protect Temporal persistence and test coordinated disaster recovery.
