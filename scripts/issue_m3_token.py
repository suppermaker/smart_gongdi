#!/usr/bin/env python3
import base64
import hashlib
import hmac
import json
import sys
import time

secret, tenant, project, actor, task, role, permissions = sys.argv[1:]
payload = json.dumps(
    {
        "tenant_id": tenant,
        "project_id": project,
        "actor_id": actor,
        "task_id": task,
        "role": role,
        "permissions": permissions.split(","),
        "exp": int(time.time()) + 600,
    },
    separators=(",", ":"),
).encode()
encode = lambda value: base64.urlsafe_b64encode(value).rstrip(b"=").decode()
print(f"{encode(payload)}.{encode(hmac.new(secret.encode(), payload, hashlib.sha256).digest())}")
