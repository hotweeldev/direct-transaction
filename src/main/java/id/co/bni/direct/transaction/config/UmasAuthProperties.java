package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bearer validation against UMAS. Faithful copy of direct-admin's class of the same name
 * (this Maven module cannot depend on that one; per-repo copies are the house pattern).
 *
 * <p>The FE holds a JWE it cannot read; the UMAS gateway unwraps it to the underlying
 * RSA-signed JWT before forwarding — so this service, deployed <b>behind that gateway</b>,
 * receives a plain JWT and validates it locally: signature against the UMAS signing
 * public key, expiry, and the POSTLOGIN scope. Optionally it also asks the UMAS
 * token-service whether the token's {@code jti} is still present (not logged out /
 * revoked) — the same presence check the gateway itself performs, fail-closed.
 *
 * <p>{@code enabled=false} (default) keeps the pre-UMAS behavior: the maker/checker
 * identity is taken from X-User-Id and the userId body/query fields as-is, which is what
 * local development without a running UMAS stack needs. Turning it on replaces X-User-Id
 * with the token's identity claim and 403s any caller-supplied identity that contradicts
 * the token.
 */
@ConfigurationProperties(prefix = "app.umas.auth")
public class UmasAuthProperties {

    private boolean enabled = false;

    /** UMAS JWT signing public key, base64 X.509 DER (same value as BNI_DIRECT_JWK_PUBLIC_KEY). */
    private String jwkPublicKey = "";

    /**
     * Scope claim the token must carry. POSTLOGIN: this service serves CORPORATE users
     * (makers, approvers, releasers), and that is the scope their token carries.
     * BANK_STAFF belongs to a bank employee and is what direct-bankmodule requires — not
     * this service, which never sees one.
     */
    private String requiredScope = "POSTLOGIN";

    /** Ask token-service whether the jti is still live. Requires token base-url. */
    private boolean presenceCheckEnabled = false;

    private String tokenBaseUrl = "";

    private String accessTokenUri = "/surrounding/token/v1/access-tokens";

    /**
     * Value for the {@code Mav-API-Key} header. The presence check calls token-service
     * directly, with no gateway in between to inject it, and UMAS rejects a request
     * without it before any controller runs.
     */
    private String mavApiKey = "";

    /** Paths the filter never touches (health, docs). */
    private List<String> excludedPaths = List.of("/actuator", "/swagger-ui", "/v3/api-docs");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwkPublicKey() {
        return jwkPublicKey;
    }

    public void setJwkPublicKey(String jwkPublicKey) {
        this.jwkPublicKey = jwkPublicKey;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public boolean isPresenceCheckEnabled() {
        return presenceCheckEnabled;
    }

    public void setPresenceCheckEnabled(boolean presenceCheckEnabled) {
        this.presenceCheckEnabled = presenceCheckEnabled;
    }

    public String getTokenBaseUrl() {
        return tokenBaseUrl;
    }

    public void setTokenBaseUrl(String tokenBaseUrl) {
        this.tokenBaseUrl = tokenBaseUrl;
    }

    public String getAccessTokenUri() {
        return accessTokenUri;
    }

    public void setAccessTokenUri(String accessTokenUri) {
        this.accessTokenUri = accessTokenUri;
    }

    public String getMavApiKey() {
        return mavApiKey;
    }

    public void setMavApiKey(String mavApiKey) {
        this.mavApiKey = mavApiKey;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
