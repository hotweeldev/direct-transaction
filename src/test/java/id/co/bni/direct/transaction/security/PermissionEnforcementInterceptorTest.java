package id.co.bni.direct.transaction.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.UmasAuthorizationProperties;
import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.integration.UmasAuthorizationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Adapted copy of direct-admin's test of the same name — the interceptor is a faithful
 * copy, so the assertions are too, over this service's resource codes.
 */
@ExtendWith(MockitoExtension.class)
class PermissionEnforcementInterceptorTest {

    @Mock
    private UmasAuthorizationClient client;

    private UmasAuthorizationProperties properties;
    private PermissionEnforcementInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /** Stands in for a real controller: one guarded handler, one that declares nothing. */
    @RequiresPermission(resource = "transfer-bni")
    static class GuardedController {
        public void submit() {
        }
    }

    static class OpenController {
        public void list() {
        }
    }

    @BeforeEach
    void setUp() {
        properties = new UmasAuthorizationProperties();
        properties.setEnabled(true);
        interceptor = new PermissionEnforcementInterceptor(properties, client, new ObjectMapper());
        request = new MockHttpServletRequest("POST", "/api/v1/companies/TUNKIN/transfers");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("companyId", "TUNKIN"));
        response = new MockHttpServletResponse();
    }

    private HandlerMethod handler(Object bean, String method) throws Exception {
        return new HandlerMethod(bean, bean.getClass().getMethod(method));
    }

    @Test
    void allowsWhenUmasAllows() throws Exception {
        request.setAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID, "ALFA");
        request.setAttribute(UmasBearerAuthFilter.ATTR_TENANT_ID, "bni-direct");
        when(client.decide(eq("ALFA"), eq("TUNKIN"), eq("bni-direct"),
                argThat(c -> c.contains(new UmasAuthorizationClient.Check("transfer-bni", "ACCESS")))))
                .thenReturn(Map.of(new UmasAuthorizationClient.Check("transfer-bni", "ACCESS"), true));

        assertThat(interceptor.preHandle(request, response, handler(new GuardedController(), "submit")))
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void deniesWithForbiddenWhenUmasDenies() throws Exception {
        request.setAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID, "ALFA");
        when(client.decide(any(), any(), any(), any())).thenReturn(Map.of());

        assertThat(interceptor.preHandle(request, response, handler(new GuardedController(), "submit")))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        // The denial body must carry neither the resource code nor UMAS's reason.
        assertThat(response.getContentAsString())
                .doesNotContain("transfer-bni")
                .contains("FORBIDDEN");
    }

    /** No verified subject, no answer — and therefore no pass. */
    @Test
    void deniesWhenThereIsNoVerifiedSubject() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler(new GuardedController(), "submit")))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(client);
    }

    /** A CORPORATE_USER decision needs a company; a route without one is a mapping error. */
    @Test
    void deniesWhenTheRouteCarriesNoCompany() throws Exception {
        request.removeAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        request.setAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID, "ALFA");

        assertThat(interceptor.preHandle(request, response, handler(new GuardedController(), "submit")))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(client);
    }

    /** An endpoint that declares nothing is untouched — that is how this ships dark. */
    @Test
    void doesNothingForAnUnannotatedHandler() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler(new OpenController(), "list")))
                .isTrue();
        verifyNoInteractions(client);
    }

    /** With the flag off even an annotated endpoint behaves exactly as it did before. */
    @Test
    void doesNothingWhenEnforcementIsOff() throws Exception {
        properties.setEnabled(false);

        assertThat(interceptor.preHandle(request, response, handler(new GuardedController(), "submit")))
                .isTrue();
        verifyNoInteractions(client);
    }

    /** CORS preflight carries no Authorization by design. */
    @Test
    void doesNothingForAPreflight() throws Exception {
        MockHttpServletRequest preflight =
                new MockHttpServletRequest("OPTIONS", "/api/v1/companies/TUNKIN/transfers");

        assertThat(interceptor.preHandle(preflight, response, handler(new GuardedController(), "submit")))
                .isTrue();
        verifyNoInteractions(client);
    }
}
