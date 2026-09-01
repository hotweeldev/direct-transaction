package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * RBAC enforcement against UMAS, over the HTTP contract in
 * {@code direct-umas/docs/RBAC_TENANT_INTEGRATION.md} §6.1. Faithful copy of
 * direct-admin's class of the same name (per-repo copies are the house pattern): a Maven
 * consumer of a Gradle platform with no artifact repository between the two speaks the
 * contract directly — same endpoint, same envelope, same headers.
 *
 * <p>{@code enabled=false} (the default) means the enforcement interceptor is not even
 * constructed: every request behaves exactly as it does today. That is the sanctioned way
 * to turn enforcement off, per §7 — a deliberate switch, never a catch block.
 *
 * <p>The timeouts are explicit and required by the guide. Exceeding either is a
 * <b>deny</b>: the authority being unreachable is precisely the condition under which
 * being permissive is worst.
 */
@ConfigurationProperties(prefix = "app.umas.authorization")
public class UmasAuthorizationProperties {

    /** Master switch for the {@code @RequiresPermission} interceptor. Off changes nothing. */
    private boolean enabled = false;

    /** Root of the service that answers {@code /internal/authorization/**} — auth-service itself, not the gateway. */
    private String baseUrl = "http://localhost:8081";

    private String decisionsUri = "/internal/authorization/v1/decisions";

    /**
     * Selects the tenant (§6, §9). Used when the bearer token carries no {@code tenantId}
     * claim; a claim on the token wins, since that is what UMAS itself issued. Must match
     * the tenant the corporate catalog is seeded under — {@code bni-direct}, per V13/V20.
     */
    private String tenantId = "bni-direct";

    /**
     * {@code Mav-API-Key}. {@code /decisions} is internal-only infrastructure with no
     * gateway on this hop to inject the header, so we carry it ourselves — UMAS rejects
     * the request before any controller without it.
     */
    private String mavApiKey = "";

    /** TCP connect budget. A timeout here denies. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Response budget. A timeout here denies. */
    private Duration readTimeout = Duration.ofSeconds(3);

    /**
     * Paths the interceptor never guards. Health and docs carry no bearer and declare no
     * resource, so this is belt-and-braces rather than a hole: an endpoint with no
     * {@code @RequiresPermission} is unaffected regardless.
     */
    private List<String> excludedPaths = List.of("/actuator", "/swagger-ui", "/v3/api-docs");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDecisionsUri() {
        return decisionsUri;
    }

    public void setDecisionsUri(String decisionsUri) {
        this.decisionsUri = decisionsUri;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getMavApiKey() {
        return mavApiKey;
    }

    public void setMavApiKey(String mavApiKey) {
        this.mavApiKey = mavApiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
