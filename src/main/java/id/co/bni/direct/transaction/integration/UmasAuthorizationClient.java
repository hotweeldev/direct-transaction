package id.co.bni.direct.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.CorrelationContext;
import id.co.bni.direct.transaction.config.UmasAuthorizationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks UMAS <em>may this corporate user reach these resources</em>, over the HTTP contract
 * in {@code RBAC_TENANT_INTEGRATION.md} §6.1 — {@code POST /internal/authorization/v1/decisions}
 * with the {@code { data, dataProtected }} envelope and the {@code Mav-API-Key} /
 * {@code Request-Id} / {@code X-Tenant-Id} headers. Faithful copy of direct-admin's
 * client of the same name (per-repo copies are the house pattern).
 *
 * <p>This service's callers are corporate users, whose identity is
 * {@code (tenant, company, user)} — §5 — so it sends {@code subjectType = "CORPORATE_USER"}
 * and a company, and a call with no company is a deny rather than a question:
 * {@code subjectId} alone does not identify a corporate user, and UMAS would be answering
 * about somebody else's roles or none at all.
 *
 * <p><b>{@code checks} is a batch.</b> One round trip covers every permission a request
 * needs; §6.1 asks for exactly this rather than a call per permission.
 *
 * <p><b>Everything that is not an explicit allow is a deny (§7).</b> Unreachable service,
 * timeout, non-2xx, unparseable body, a body whose {@code decisions} do not answer the
 * resource <em>and</em> action asked about — all of it comes back {@code false}. There is
 * no fail-open path here and none may be added.
 */
public class UmasAuthorizationClient {

    private static final Logger log = LoggerFactory.getLogger(UmasAuthorizationClient.class);

    /** One {@code resource:action} pair to ask about. */
    public record Check(String resource, String action) {
    }

    private final UmasAuthorizationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public UmasAuthorizationClient(UmasAuthorizationProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(timedRequestFactory(properties))
                .build());
    }

    /** Seam for tests: hand in a RestClient bound to a mock server or a stub factory. */
    public UmasAuthorizationClient(UmasAuthorizationProperties properties, ObjectMapper objectMapper,
                                   RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static JdkClientHttpRequestFactory timedRequestFactory(UmasAuthorizationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    /**
     * Convenience for the single-permission case. Still travels as a one-element
     * {@code checks} array — the contract has no per-permission endpoint.
     */
    public boolean isAllowed(String subjectId, String companyId, String tenantId,
                             String resource, String action) {
        Check check = new Check(resource, action);
        return decide(subjectId, companyId, tenantId, List.of(check))
                .getOrDefault(check, Boolean.FALSE);
    }

    /**
     * @return one entry per requested check. A check the response did not answer maps to
     *         {@code false}; the map never contains an entry that was not asked for.
     */
    public Map<Check, Boolean> decide(String subjectId, String companyId, String tenantId,
                                      List<Check> checks) {
        Map<Check, Boolean> denied = new LinkedHashMap<>();
        for (Check check : checks) {
            denied.put(check, Boolean.FALSE);
        }
        if (isBlank(subjectId) || checks.isEmpty()) {
            // No verified subject means no answer can be given, so the answer is no (§7).
            return denied;
        }
        if (isBlank(companyId)) {
            // A CORPORATE_USER without a company is not an identity (§5). Asking anyway
            // would have UMAS answer about a different subject, which is worse than a deny.
            log.warn("Authorization asked for corporate subject={} with no company - denying "
                    + "(fail-closed)", subjectId);
            return denied;
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.put("subjectId", subjectId);
        // CORPORATE_USER, not BANK_STAFF: this service fronts corporate transaction
        // screens, whose users hold roles through their company's groups (§5).
        data.put("subjectType", "CORPORATE_USER");
        data.put("companyId", companyId);
        ArrayNode checkNodes = data.putArray("checks");
        for (Check check : checks) {
            ObjectNode node = checkNodes.addObject();
            node.put("resource", check.resource());
            node.put("action", check.action());
        }

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.set("data", data);
        envelope.set("dataProtected", objectMapper.createObjectNode());

        JsonNode body;
        try {
            body = restClient.post()
                    .uri(properties.getDecisionsUri())
                    .header("Mav-API-Key", properties.getMavApiKey())
                    .header("Request-Id", requestId())
                    .header("X-Tenant-Id", isBlank(tenantId) ? properties.getTenantId() : tenantId)
                    .body(envelope)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            // UMS-005-001/-002/-005 fail the whole request by design (§7).
                            log.warn("UMAS decisions denied subject={} company={} status={}",
                                    subjectId, companyId, response.getStatusCode());
                            return null;
                        }
                        return response.bodyTo(JsonNode.class);
                    });
        } catch (Exception e) {
            // MUST NEVER BECOME FAIL-OPEN. Unreachable UMAS, a connect or read timeout, a
            // broken connection and a malformed body all land here, and every one of them
            // is a deny (RBAC_TENANT_INTEGRATION.md §7). The only sanctioned way to let
            // traffic past is app.umas.authorization.enabled=false — a deliberate
            // configuration switch. Do not "temporarily" return true from this block.
            log.warn("UMAS authorization unavailable, denying (fail-closed): subject={} company={} "
                    + "error={}", subjectId, companyId, e.toString());
            return denied;
        }

        if (body == null) {
            return denied;
        }
        JsonNode decisions = body.path("data").path("decisions");
        if (!decisions.isArray()) {
            log.warn("UMAS decisions response unparseable, denying (fail-closed): subject={}", subjectId);
            return denied;
        }

        Map<Check, Boolean> result = new LinkedHashMap<>(denied);
        for (JsonNode decision : decisions) {
            // Match resource AND action. A decision about a different pair answers nothing
            // about the one we asked, and must not be allowed to satisfy it.
            Check answered = new Check(decision.path("resource").asText(null),
                    decision.path("action").asText(null));
            if (!result.containsKey(answered)) {
                continue;
            }
            boolean allowed = decision.path("allowed").isBoolean() && decision.path("allowed").asBoolean();
            result.put(answered, allowed);
            if (!allowed) {
                // reason is for logs and the support desk only, never for an end user (§6.1):
                // it describes the authorisation model to whoever is asking.
                log.info("UMAS denied subject={} company={} {}:{} reason={}", subjectId, companyId,
                        answered.resource(), answered.action(),
                        decision.path("reason").asText("UNSPECIFIED"));
            }
        }
        return result;
    }

    private String requestId() {
        String correlationId = CorrelationContext.currentCorrelationId();
        return "-".equals(correlationId) ? "trx-authz" : correlationId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
