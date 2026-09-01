package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The UMAS authenticator - OTP challenge issuance and transaction verification. Same
 * header conventions as direct-admin's {@code Umas*Client} classes (X-Tenant-Id,
 * Request-Id, Mav-API-Key), bound here so OCP secrets supply the real values.
 */
@ConfigurationProperties(prefix = "app.authenticator")
public class AuthenticatorProperties {

    /** UMAS base URL serving /internal/authenticator/v1. */
    private String baseUrl = "http://localhost:8085";

    /** Sent as X-Tenant-Id on every call. */
    private String tenantId = "default";

    /**
     * Sent as Mav-API-Key when non-empty - the header direct-admin's UMAS clients carry so
     * api-security can tell which system is calling. Empty for a local UMAS without it.
     */
    private String mavApiKey = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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
}
