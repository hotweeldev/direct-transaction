package id.co.bni.direct.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.integration.UmasTokenPresenceClient;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which claim the bearer filter reads as the caller's identity, what it exposes to the
 * binding interceptor, and the one divergence from direct-admin's copy: an X-User-Id that
 * contradicts the token is a 403, not a silent override.
 */
class UmasBearerAuthFilterIdentityTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static UmasBearerAuthFilter filter() {
        UmasAuthProperties properties = new UmasAuthProperties();
        properties.setEnabled(true);
        properties.setRequiredScope("POSTLOGIN");
        properties.setJwkPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return new UmasBearerAuthFilter(properties, mock(UmasTokenPresenceClient.class), new ObjectMapper());
    }

    private static String token(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate())
                .compact();
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/companies/TUNKIN/transfers");
    }

    /** Runs the filter and returns the X-User-Id the chain saw, or null when rejected. */
    private static String identitySeenDownstream(MockHttpServletRequest request,
                                                 MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(((HttpServletRequest) req).getHeader("X-User-Id"));
        filter().doFilter(request, response, chain);
        return seen.get();
    }

    @Test
    void acceptsACorporateUsersTokenAndTakesUserIdAsTheIdentity() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer "
                + token(Map.of("scope", "POSTLOGIN", "user_id", "ALFA", "company_id", "TUNKIN")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = identitySeenDownstream(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen).isEqualTo("ALFA");
        assertThat(request.getAttribute(UmasBearerAuthFilter.ATTR_COMPANY_ID)).isEqualTo("TUNKIN");
    }

    /** Shape compatibility: an X-User-Id AGREEING with the token passes untouched. */
    @Test
    void acceptsAnXUserIdThatMatchesTheToken() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer "
                + token(Map.of("scope", "POSTLOGIN", "user_id", "ALFA", "company_id", "TUNKIN")));
        request.addHeader("X-User-Id", "alfa"); // case-insensitive, like the URL matching
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = identitySeenDownstream(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        // Downstream still sees the token's own casing - the token is the identity.
        assertThat(seen).isEqualTo("ALFA");
    }

    /** The divergence from direct-admin: a contradicting X-User-Id is refused loudly. */
    @Test
    void rejectsAnXUserIdThatContradictsTheToken() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer "
                + token(Map.of("scope", "POSTLOGIN", "user_id", "ALFA", "company_id", "TUNKIN")));
        request.addHeader("X-User-Id", "BRAVO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = identitySeenDownstream(request, response);

        assertThat(seen).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        // Opaque: names neither identity.
        assertThat(response.getContentAsString())
                .contains("FORBIDDEN")
                .doesNotContain("ALFA")
                .doesNotContain("BRAVO");
    }

    @Test
    void rejectsAMissingBearer() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        identitySeenDownstream(request(), response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void rejectsATokenCarryingNeitherIdentityClaim() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + token(Map.of("scope", "POSTLOGIN")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        identitySeenDownstream(request, response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("no user identity");
    }

    @Test
    void refusesTheWrongScope() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer "
                + token(Map.of("scope", "BANK_STAFF", "staff_id", "62509")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        identitySeenDownstream(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN_SCOPE");
    }
}
