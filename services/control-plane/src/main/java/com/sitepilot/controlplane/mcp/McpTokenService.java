package com.sitepilot.controlplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpTokenService {
    private final ObjectMapper json;
    private final byte[] secret;
    public String issue(McpPrincipal principal) {
        try {
            byte[] payload = json.writeValueAsBytes(java.util.Map.of(
                    "tenant_id", principal.tenantId(), "project_id", principal.projectId(),
                    "actor_id", principal.actorId(), "task_id", principal.taskId(),
                    "role", principal.role(), "permissions", principal.permissions(),
                    "exp", principal.expiresAt()));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("token cannot be issued", exception);
        }
    }

    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public McpTokenService(ObjectMapper json, @Value("${sitepilot.mcp.hmac-secret:}") String secret) {
        this(json, secret, Clock.systemUTC());
    }

    McpTokenService(ObjectMapper json, String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("SITEPILOT_MCP_HMAC_SECRET must contain at least 32 characters");
        }
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public McpPrincipal verify(String authorization) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new SecurityException("missing bearer token");
            }
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 2) throw new SecurityException("malformed bearer token");
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), supplied)) {
                throw new SecurityException("invalid bearer token");
            }
            JsonNode claims = json.readTree(payload);
            long exp = requiredLong(claims, "exp");
            if (exp <= Instant.now(clock).getEpochSecond()) {
                throw new SecurityException("expired bearer token");
            }
            var permissions = new HashSet<String>();
            JsonNode values = claims.get("permissions");
            if (values == null || !values.isArray()) {
                throw new SecurityException("permissions claim required");
            }
            values.forEach(value -> permissions.add(value.asText()));
            return new McpPrincipal(required(claims, "tenant_id"), required(claims, "project_id"),
                    required(claims, "actor_id"), required(claims, "task_id"), required(claims, "role"),
                    Set.copyOf(permissions), exp);
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("invalid bearer token", exception);
        }
    }

    private byte[] sign(byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private String required(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) throw new SecurityException(name + " claim required");
        return value;
    }

    private long requiredLong(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).canConvertToLong()) {
            throw new SecurityException(name + " claim required");
        }
        return node.get(name).asLong();
    }
}
