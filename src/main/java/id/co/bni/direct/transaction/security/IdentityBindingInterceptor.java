package id.co.bni.direct.transaction.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.dto.response.CommonResponses.ErrorMessageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Binds the verified token identity to the identities the request names. Runs after
 * {@link UmasBearerAuthFilter} (a servlet filter always precedes an interceptor) and
 * before {@link PermissionEnforcementInterceptor}; modeled on that interceptor — same
 * placement, same opaque denial body.
 *
 * <p>The rules, both held against the signed token rather than anything the caller can
 * vary:
 * <ul>
 *   <li>the path's {@code {companyId}} must equal the token's {@code company_id} claim —
 *       a caller may never read or write another company's data, whatever the path
 *       says;</li>
 *   <li>a {@code userId} query parameter, where a route carries one (the inbox, the task
 *       detail), must equal the token's own user identity. The parameter stays accepted
 *       for wire-shape compatibility — it just may no longer name somebody else.</li>
 * </ul>
 * The X-User-Id header is checked (and then overridden) by the filter itself, and the
 * {@code userId} <em>body</em> fields are checked in the controllers via
 * {@link TokenIdentity} — a JSON body cannot be read here without consuming it.
 *
 * <p>Comparison is case-insensitive because URL matching in this service is
 * case-insensitive (see {@code WebMvcConfig}).
 *
 * <p>Constructed only when {@code app.umas.auth.enabled=true}; with the flag off nothing
 * is registered and the service behaves exactly as before.
 */
public class IdentityBindingInterceptor implements HandlerInterceptor {

    static final String COMPANY_ID_VARIABLE = "companyId";
    static final String USER_ID_PARAMETER = "userId";

    private static final Logger log = LoggerFactory.getLogger(IdentityBindingInterceptor.class);

    private final ObjectMapper objectMapper;
    private final List<String> excludedPaths;

    public IdentityBindingInterceptor(ObjectMapper objectMapper, List<String> excludedPaths) {
        this.objectMapper = objectMapper;
        this.excludedPaths = excludedPaths;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS preflight carries no Authorization by design
        }
        String path = request.getRequestURI();
        if (excludedPaths.stream().anyMatch(path::startsWith)) {
            return true;
        }

        Object subject = request.getAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID);
        if (subject == null || subject.toString().isBlank()) {
            // The filter rejects unauthenticated calls before this runs; a request with no
            // verified subject here is a wiring error, and it denies — never waves through.
            log.error("Identity binding reached with no verified subject on {} - denying.", path);
            deny(response);
            return false;
        }

        Object pathCompany = uriVariables(request).get(COMPANY_ID_VARIABLE);
        if (pathCompany != null) {
            Object tokenCompany = request.getAttribute(UmasBearerAuthFilter.ATTR_COMPANY_ID);
            if (tokenCompany == null || tokenCompany.toString().isBlank()
                    || !pathCompany.toString().equalsIgnoreCase(tokenCompany.toString())) {
                log.info("Denied subject={} path company={} token company={} path={}",
                        subject, pathCompany, tokenCompany, path);
                deny(response);
                return false;
            }
        }

        // Query string only: our POST bodies are JSON, so getParameter never touches them.
        String queryUser = request.getParameter(USER_ID_PARAMETER);
        if (queryUser != null && !queryUser.isBlank()
                && !queryUser.equalsIgnoreCase(subject.toString())) {
            log.info("Denied subject={} asked with userId={} path={}", subject, queryUser, path);
            deny(response);
            return false;
        }
        return true;
    }

    private static Map<?, ?> uriVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? map : Map.of();
    }

    /** Same opaque body as the permission interceptor — no detail for a caller to probe with. */
    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorMessageResponse("Akses ditolak", "FORBIDDEN"));
    }
}
