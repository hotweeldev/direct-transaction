package id.co.bni.direct.transaction.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The identity-binding contract on the transfer/task routes: the path {companyId} must be
 * the company the token was minted for, and a userId query parameter — kept for wire-shape
 * compatibility — may no longer name somebody else. Plus {@link TokenIdentity}, the same
 * rule for the userId BODY fields the controllers check.
 */
class IdentityBindingInterceptorTest {

    static class SomeController {
        public void handle() {
        }
    }

    private IdentityBindingInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private HandlerMethod handler;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new IdentityBindingInterceptor(new ObjectMapper(),
                List.of("/actuator", "/swagger-ui", "/v3/api-docs"));
        request = new MockHttpServletRequest("GET", "/api/v1/companies/TUNKIN/tasks");
        response = new MockHttpServletResponse();
        handler = new HandlerMethod(new SomeController(), SomeController.class.getMethod("handle"));
    }

    private void authenticatedAs(String userId, String companyId) {
        request.setAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID, userId);
        request.setAttribute(UmasBearerAuthFilter.ATTR_COMPANY_ID, companyId);
    }

    private void pathNamesCompany(String companyId) {
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("companyId", companyId));
    }

    @Test
    void allowsTheCompanyTheTokenWasMintedFor() throws Exception {
        authenticatedAs("ALFA", "TUNKIN");
        pathNamesCompany("TUNKIN");

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void deniesAnotherCompanysPath() throws Exception {
        authenticatedAs("ALFA", "TUNKIN");
        pathNamesCompany("OTHERCO");

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** A token minted with no company claim cannot satisfy a company-scoped path. */
    @Test
    void deniesWhenTheTokenCarriesNoCompany() throws Exception {
        request.setAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID, "ALFA");
        pathNamesCompany("TUNKIN");

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** The inbox/detail shape: ?userId= stays accepted but may not name somebody else. */
    @Test
    void allowsAUserIdParameterNamingTheCallerAndDeniesOneNamingAnybodyElse() throws Exception {
        authenticatedAs("ALFA", "TUNKIN");
        pathNamesCompany("TUNKIN");
        request.setParameter("userId", "alfa"); // case-insensitive, like the URL matching
        assertThat(interceptor.preHandle(request, response, handler)).isTrue();

        request.setParameter("userId", "BRAVO");
        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        // Opaque body - no identity echoed for a caller to probe with.
        assertThat(response.getContentAsString()).contains("FORBIDDEN").doesNotContain("BRAVO");
    }

    /** No verified subject means a wiring error, and a wiring error denies. */
    @Test
    void deniesWhenThereIsNoVerifiedSubject() throws Exception {
        pathNamesCompany("TUNKIN");

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** CORS preflight carries no Authorization by design. */
    @Test
    void doesNothingForAPreflight() throws Exception {
        MockHttpServletRequest preflight =
                new MockHttpServletRequest("OPTIONS", "/api/v1/companies/TUNKIN/tasks");

        assertThat(interceptor.preHandle(preflight, response, handler)).isTrue();
    }

    // ------------------------------------------------------------------
    // TokenIdentity - the body-field leg the controllers invoke.
    // ------------------------------------------------------------------

    @Test
    void tokenIdentityAcceptsTheCallersOwnUserIdInABody() {
        authenticatedAs("ALFA", "TUNKIN");

        assertThatCode(() -> TokenIdentity.requireSameUser(request, "ALFA"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TokenIdentity.requireSameUser(request, "alfa"))
                .doesNotThrowAnyException();
    }

    @Test
    void tokenIdentityRefusesABodyUserIdNamingAnybodyElse() {
        authenticatedAs("ALFA", "TUNKIN");

        assertThatThrownBy(() -> TokenIdentity.requireSameUser(request, "BRAVO"))
                .isInstanceOf(ForbiddenException.class);
    }

    /** With bearer validation off there is no token to bind to - trusted as before UMAS. */
    @Test
    void tokenIdentityDoesNothingWhenNoSubjectWasVerified() {
        assertThatCode(() -> TokenIdentity.requireSameUser(request, "ANYONE"))
                .doesNotThrowAnyException();
    }
}
