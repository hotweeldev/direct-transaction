package id.co.bni.direct.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.integration.UmasAuthorizationClient;
import id.co.bni.direct.transaction.integration.UmasTokenPresenceClient;
import id.co.bni.direct.transaction.security.IdentityBindingInterceptor;
import id.co.bni.direct.transaction.security.PermissionEnforcementInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UMAS integration wiring — bearer validation, identity binding, and RBAC enforcement,
 * following direct-admin's {@code UmasIntegrationConfig} (per-repo copies are the house
 * pattern).
 *
 * <p>With {@code app.umas.auth.enabled=false} (the default) none of this is constructed
 * and the service behaves exactly as before — X-User-Id and the userId body/query fields
 * are trusted as sent — which is what local development without a running UMAS stack
 * needs. {@code app.umas.authorization.enabled} additionally gates the
 * {@code @RequiresPermission} interceptor, and requires bearer validation to be on
 * ({@code UmasEnforcementStartupCheck} refuses to boot otherwise).
 */
@Configuration
@EnableConfigurationProperties({ UmasAuthProperties.class, UmasAuthorizationProperties.class })
public class UmasIntegrationConfig {

    /**
     * Unlike direct-admin, this client has no caller besides endpoint enforcement, so it
     * is gated by the same flag as the interceptor it serves.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.umas.authorization", name = "enabled", havingValue = "true")
    public UmasAuthorizationClient umasAuthorizationClient(UmasAuthorizationProperties properties,
                                                           ObjectMapper objectMapper) {
        return new UmasAuthorizationClient(properties, objectMapper);
    }

    /**
     * Endpoint enforcement, off by default. With the flag off the interceptor is not
     * constructed, nothing is registered on the handler chain, and no
     * {@code @RequiresPermission} endpoint is guarded — the service behaves exactly as it
     * does today. That is the sanctioned off switch of RBAC_TENANT_INTEGRATION.md §7.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.umas.authorization", name = "enabled", havingValue = "true")
    public PermissionEnforcementInterceptor permissionEnforcementInterceptor(
            UmasAuthorizationProperties properties,
            UmasAuthorizationClient client,
            ObjectMapper objectMapper) {
        return new PermissionEnforcementInterceptor(properties, client, objectMapper);
    }

    /** Fails the context rather than letting a doomed enforcement configuration boot. */
    @Bean
    public UmasEnforcementStartupCheck umasEnforcementStartupCheck(
            UmasAuthorizationProperties authorization, UmasAuthProperties auth) {
        return new UmasEnforcementStartupCheck(authorization, auth);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.umas.auth", name = "enabled", havingValue = "true")
    public UmasTokenPresenceClient umasTokenPresenceClient(UmasAuthProperties properties) {
        return new UmasTokenPresenceClient(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.umas.auth", name = "enabled", havingValue = "true")
    public UmasBearerAuthFilter umasBearerAuthFilter(UmasAuthProperties properties,
                                                     UmasTokenPresenceClient presenceClient,
                                                     ObjectMapper objectMapper) {
        return new UmasBearerAuthFilter(properties, presenceClient, objectMapper);
    }

    /**
     * Binds the verified token to the path {companyId} and any userId query parameter.
     * Gated by the same flag as the filter: binding without a verified subject would deny
     * everything, and a verified subject without binding would leave the path variables
     * unchecked — the two only make sense together.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.umas.auth", name = "enabled", havingValue = "true")
    public IdentityBindingInterceptor identityBindingInterceptor(UmasAuthProperties properties,
                                                                 ObjectMapper objectMapper) {
        return new IdentityBindingInterceptor(objectMapper, properties.getExcludedPaths());
    }
}
