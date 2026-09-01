package id.co.bni.direct.transaction.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Registers the host-matching CORS rule as a servlet filter.
 *
 * <p>spine-backoffice hands its {@code CorsConfigurationSource} to Spring Security's
 * {@code .cors(...)} instead. This service has no security starter yet, so the filter is
 * registered directly. When auth is added, delete this class and wire the same
 * {@code corsConfigurationSource} bean into the filter chain - leaving both in place means
 * two {@code Access-Control-Allow-Origin} headers on every response, which browsers reject.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        return new HostMatchingCorsConfigurationSource(properties);
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(
            // By name, not by type: Spring MVC registers mvcHandlerMappingIntrospector, which
            // also implements CorsConfigurationSource, so a by-type injection is ambiguous.
            @Qualifier("corsConfigurationSource") CorsConfigurationSource source) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // Ahead of everything else: a rejected preflight must not run application filters.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
