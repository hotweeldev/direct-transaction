package id.co.bni.direct.transaction.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.UmasAuthorizationProperties;
import id.co.bni.direct.transaction.config.UmasBearerAuthFilter;
import id.co.bni.direct.transaction.dto.response.CommonResponses.ErrorMessageResponse;
import id.co.bni.direct.transaction.integration.UmasAuthorizationClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The enforcement point: after authentication, before the controller. Faithful copy of
 * direct-admin's interceptor of the same name (per-repo copies are the house pattern).
 *
 * <p><b>Why an interceptor and not the bearer filter.</b> {@link UmasBearerAuthFilter}
 * runs before Spring MVC has picked a handler, so it cannot see
 * {@link RequiresPermission} — it would have to re-derive the resource from the URL,
 * which is the path-to-resource table the annotation exists to avoid. A
 * {@link HandlerInterceptor} is handed the resolved {@link HandlerMethod}, so the check
 * reads the declaration off the very method it is about to permit. Servlet filters always
 * run before interceptors, so ordering gives us the property we need for free: <b>an
 * unauthenticated caller is rejected by the filter and never reaches this class</b>, and
 * therefore never learns whether a resource exists.
 *
 * <p><b>Where the company comes from.</b> Every guarded route here is
 * {@code /api/v1/companies/{companyId}/…}, and the path is the authority. Spring resolves
 * URI template variables while selecting the handler, so they are on the request before
 * {@code preHandle} runs. Asking UMAS about the path's company means a caller who reaches
 * for another company's data is asked about <em>that</em> company, where they hold
 * nothing, and is denied by the decision itself. (In this service
 * {@code IdentityBindingInterceptor} additionally requires the token's own company claim
 * to equal the path — both checks hold, in that order.)
 *
 * <p>Denies, always (RBAC_TENANT_INTEGRATION.md §7): UMAS says {@code allowed: false} or
 * answers about a different resource/action; UMAS is unreachable, times out, or answers
 * unparseably; the endpoint declares a resource but the request carries no verified
 * subject; the endpoint declares a resource but the route carries no {@code companyId}.
 *
 * <p>Does nothing at all when the handler declares no {@code @RequiresPermission}. That is
 * how this ships dark: only annotated endpoints are gated, and the flag gates even those.
 */
public class PermissionEnforcementInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionEnforcementInterceptor.class);

    /** The path variable every corporate route carries. */
    static final String COMPANY_ID_VARIABLE = "companyId";

    private final UmasAuthorizationProperties properties;
    private final UmasAuthorizationClient client;
    private final ObjectMapper objectMapper;

    public PermissionEnforcementInterceptor(UmasAuthorizationProperties properties,
                                            UmasAuthorizationClient client,
                                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.isEnabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS preflight carries no Authorization by design
        }
        String path = request.getRequestURI();
        if (properties.getExcludedPaths().stream().anyMatch(path::startsWith)) {
            return true;
        }

        RequiresPermission policy = findPolicy(method);
        if (policy == null) {
            // Not opted in. Behaves exactly as before this class existed.
            return true;
        }

        Object subjectId = request.getAttribute(UmasBearerAuthFilter.ATTR_STAFF_ID);
        if (subjectId == null || subjectId.toString().isBlank()) {
            // An endpoint naming a resource with no verified subject cannot be answered,
            // so it is denied — never waved through. This is the misconfiguration of
            // running enforcement with app.umas.auth.enabled=false; say so loudly.
            log.error("{} declares {}:{} but the request carries no verified subject - denying. "
                            + "app.umas.authorization.enabled=true requires app.umas.auth.enabled=true.",
                    method.getShortLogMessage(), policy.resource(), policy.action());
            deny(response);
            return false;
        }

        String companyId = companyIdOf(request);
        if (companyId == null || companyId.isBlank()) {
            // A CORPORATE_USER decision needs a company (§5). An annotated route that does
            // not carry one is a mapping error, and it denies rather than asks a question
            // UMAS would answer about somebody else.
            log.error("{} declares {}:{} but the route carries no {} - denying.",
                    method.getShortLogMessage(), policy.resource(), policy.action(), COMPANY_ID_VARIABLE);
            deny(response);
            return false;
        }

        Object tenantId = request.getAttribute(UmasBearerAuthFilter.ATTR_TENANT_ID);

        // One batched question, not one call per candidate: UMAS answers a list in a single
        // round trip, and asking sequentially would make a two-resource endpoint twice as slow
        // for every caller who happens to match the second one.
        List<UmasAuthorizationClient.Check> checks = new ArrayList<>();
        checks.add(new UmasAuthorizationClient.Check(policy.resource(), policy.action()));
        for (String alternative : policy.alsoAllow()) {
            checks.add(new UmasAuthorizationClient.Check(alternative, policy.action()));
        }

        boolean allowed = client.decide(subjectId.toString(), companyId,
                        tenantId == null ? null : tenantId.toString(), checks)
                .values().stream().anyMatch(Boolean::booleanValue);
        if (!allowed) {
            log.info("Denied subject={} company={} {}:{} path={}", subjectId, companyId,
                    policy.resource(), policy.action(), path);
            deny(response);
            return false;
        }
        return true;
    }

    /** Method declaration wins over the controller class it sits in. */
    private RequiresPermission findPolicy(HandlerMethod method) {
        RequiresPermission onMethod =
                AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresPermission.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPermission.class);
    }

    /** The {@code {companyId}} the handler mapping already bound, or null. */
    private static String companyIdOf(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(COMPANY_ID_VARIABLE);
        return value == null ? null : value.toString();
    }

    /**
     * One body for every denial, carrying no resource code and no UMAS {@code reason} —
     * a caller must not be able to probe the catalog, and §6.1 says the reason is for our
     * logs, not for an end user.
     */
    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorMessageResponse("Akses ditolak", "FORBIDDEN"));
    }
}
