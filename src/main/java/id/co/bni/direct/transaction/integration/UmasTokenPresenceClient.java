package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.bni.direct.transaction.config.UmasAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Presence check against the UMAS token-service: is this {@code jti} still alive in
 * Redis (not logged out, not revoked)? Faithful copy of direct-admin's client of the
 * same name (per-repo copies are the house pattern). Mirrors the gateway simulator's
 * {@code tokenPresence.js}: GET {@code /surrounding/token/v1/access-tokens?jti=} and a
 * live token answers with a non-empty {@code data.value}.
 *
 * <p>Fail-closed: an unreachable token-service means "not present". A revoked token
 * slipping through is worse than a retry.
 */
public class UmasTokenPresenceClient {

    private static final Logger log = LoggerFactory.getLogger(UmasTokenPresenceClient.class);

    private final UmasAuthProperties properties;
    private final RestClient restClient;

    public UmasTokenPresenceClient(UmasAuthProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getTokenBaseUrl()).build();
    }

    public boolean isTokenPresent(String jti, String tenantId) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            JsonNode body = restClient.get()
                    .uri(uri -> uri.path(properties.getAccessTokenUri()).queryParam("jti", jti).build())
                    .header("X-Tenant-Id", tenantId == null ? "default" : tenantId)
                    .header("Request-Id", "trx-presence-" + jti)
                    // No gateway on this hop, so we carry UMAS's api-security header
                    // ourselves - without it the call is rejected before the controller.
                    .header("Mav-API-Key", properties.getMavApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            String value = body == null ? null : body.path("data").path("value").asText(null);
            return value != null && !value.isEmpty();
        } catch (Exception e) {
            log.warn("Token presence check failed (fail-closed): {}", e.getMessage());
            return false;
        }
    }
}
