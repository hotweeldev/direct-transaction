package id.co.bni.direct.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.dto.response.CommonResponses.ErrorMessageResponse;
import id.co.bni.direct.transaction.integration.UmasTokenPresenceClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Validates the Bearer token the UMAS gateway forwards (JWT — the gateway already
 * unwrapped the FE's JWE) and replaces the caller-supplied X-User-Id with the token's own
 * identity claim, so the maker/checker identity downstream comes from the signed token,
 * never from a header the browser could fabricate. Copy of direct-admin's filter of the
 * same name (per-repo copies are the house pattern), with ONE deliberate divergence: an
 * X-User-Id that is present but contradicts the token is rejected with 403
 * {@code FORBIDDEN} rather than silently overridden — the transfer endpoints still accept
 * the header for wire-shape compatibility, and a client asserting somebody else's
 * identity is an attack to refuse loudly, not a value to fix up quietly.
 *
 * <p>Which claim that is depends on who logged in: {@code user_id} for the corporate
 * users this service serves, {@code staff_id} for a bank employee.
 *
 * <p>Rejections: missing/garbled token and bad signature → 401 {@code UNAUTHORIZED};
 * expired → 401 {@code TOKEN_EXPIRED}; wrong scope → 403 {@code FORBIDDEN_SCOPE};
 * X-User-Id contradicting the token → 403 {@code FORBIDDEN}; presence check says
 * revoked/logged-out (or token-service unreachable — fail-closed) → 401
 * {@code TOKEN_REVOKED}.
 */
public class UmasBearerAuthFilter extends OncePerRequestFilter implements Ordered {

    public static final String ATTR_STAFF_ID = "umas.staffId";
    public static final String ATTR_BRANCH_CODE = "umas.branchCode";

    /** The company the token was minted for. Null on a bank-staff token, which has none. */
    public static final String ATTR_COMPANY_ID = "umas.companyId";

    /**
     * The token's own tenant, which wins over the configured default when an authorization
     * decision is asked (RBAC_TENANT_INTEGRATION.md §6 — {@code X-Tenant-Id} selects the
     * tenant, and the one UMAS minted the token under is the authoritative answer).
     */
    public static final String ATTR_TENANT_ID = "umas.tenantId";

    private static final Logger log = LoggerFactory.getLogger(UmasBearerAuthFilter.class);

    private final UmasAuthProperties properties;
    private final UmasTokenPresenceClient presenceClient;
    private final ObjectMapper objectMapper;
    private final PublicKey signingKey;

    public UmasBearerAuthFilter(UmasAuthProperties properties,
                                UmasTokenPresenceClient presenceClient,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.presenceClient = presenceClient;
        this.objectMapper = objectMapper;
        this.signingKey = properties.isEnabled() ? loadPublicKey(properties.getJwkPublicKey()) : null;
    }

    /** Fail at boot, not on the first request, when the key is missing or unparseable. */
    private static PublicKey loadPublicKey(String base64Der) {
        if (base64Der == null || base64Der.isBlank()) {
            throw new IllegalStateException(
                    "app.umas.auth.enabled=true requires app.umas.auth.jwk-public-key "
                            + "(base64 X.509 DER of the UMAS JWT signing public key)");
        }
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der)));
        } catch (Exception e) {
            throw new IllegalStateException("app.umas.auth.jwk-public-key is not a valid RSA X.509 key", e);
        }
    }

    @Override
    public int getOrder() {
        // After correlation (HIGHEST+10) so rejections carry a correlation id in the log.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS preflight carries no Authorization by design
        }
        String path = request.getRequestURI();
        return properties.getExcludedPaths().stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, 401, "Missing bearer token", "UNAUTHORIZED");
            return;
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(header.substring("Bearer ".length()))
                    .getPayload();
        } catch (ExpiredJwtException e) {
            reject(response, 401, "Token expired", "TOKEN_EXPIRED");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Bearer rejected: {}", e.getMessage());
            reject(response, 401, "Invalid bearer token", "UNAUTHORIZED");
            return;
        }

        String scope = claims.get("scope", String.class);
        if (!properties.getRequiredScope().equals(scope)) {
            reject(response, 403, "Scope " + scope + " is not allowed here", "FORBIDDEN_SCOPE");
            return;
        }

        // Which claim carries the identity depends on who logged in: a corporate user's
        // POSTLOGIN token carries user_id and company_id (TokenIssuanceService); a bank
        // employee's BANK_STAFF token carries staff_id. This service serves corporate
        // users, so user_id is tried first; staff_id remains a fallback so a deployment
        // pointed at BANK_STAFF still resolves an identity rather than a blank one.
        String userId = claims.get("user_id", String.class);
        if (userId == null || userId.isBlank()) {
            userId = claims.get("staff_id", String.class);
        }
        if (userId == null || userId.isBlank()) {
            reject(response, 401, "Token carries no user identity", "UNAUTHORIZED");
            return;
        }

        // The divergence from direct-admin's copy (which overrides silently): a supplied
        // X-User-Id that names somebody else is a 403, per the identity-binding contract.
        // The header stays accepted for wire-shape compatibility - it just may no longer
        // disagree with the signed token. Opaque body: no echo of either identity.
        String suppliedActor = request.getHeader("X-User-Id");
        if (suppliedActor != null && !suppliedActor.isBlank()
                && !suppliedActor.equalsIgnoreCase(userId)) {
            log.info("X-User-Id={} contradicts the token identity {} - rejecting",
                    suppliedActor, userId);
            reject(response, 403, "Akses ditolak", "FORBIDDEN");
            return;
        }

        if (properties.isPresenceCheckEnabled()
                && !presenceClient.isTokenPresent(claims.getId(), claims.get("tenantId", String.class))) {
            reject(response, 401, "Token is no longer active", "TOKEN_REVOKED");
            return;
        }

        request.setAttribute(ATTR_STAFF_ID, userId);
        // Null for a corporate token, which has no branch. Kept for the bank-staff case.
        request.setAttribute(ATTR_BRANCH_CODE, claims.get("branch_code", String.class));
        // The company the token was minted for. Signed, so unlike a path segment it cannot
        // be swapped for somebody else's company id. IdentityBindingInterceptor holds the
        // path {companyId} against this value.
        request.setAttribute(ATTR_COMPANY_ID, claims.get("company_id", String.class));
        request.setAttribute(ATTR_TENANT_ID, claims.get("tenantId", String.class));

        chain.doFilter(new StaffIdentityRequest(request, userId), response);
    }

    private void reject(HttpServletResponse response, int status, String message, String errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorMessageResponse(message, errorCode));
    }

    /** Overrides X-User-Id with the token's identity — the one identity downstream trusts. */
    private static final class StaffIdentityRequest extends HttpServletRequestWrapper {

        private final String staffId;

        StaffIdentityRequest(HttpServletRequest request, String staffId) {
            super(request);
            this.staffId = staffId;
        }

        @Override
        public String getHeader(String name) {
            return "X-User-Id".equalsIgnoreCase(name) ? staffId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "X-User-Id".equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(staffId))
                    : super.getHeaders(name);
        }
    }
}
