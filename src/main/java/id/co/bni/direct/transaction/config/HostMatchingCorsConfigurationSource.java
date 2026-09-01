package id.co.bni.direct.transaction.config;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Matches an incoming {@code Origin} on host alone, so a configured entry covers a page
 * served over either scheme or from any port.
 *
 * <p>Same rule as spine-backoffice, deliberately: the MFEs are served from the same hosts
 * against both services, and two different CORS rules would mean an origin that works on
 * one API and fails on the other.
 *
 * <p>Consequence worth knowing: an entry written as {@code https://host} or
 * {@code host:5000} matches nothing, because it is compared against a bare host name.
 * Configure hosts only.
 */
public class HostMatchingCorsConfigurationSource implements CorsConfigurationSource {

    private static final long PREFLIGHT_MAX_AGE_SECONDS = 600L;

    private final CorsConfiguration configuration;

    public HostMatchingCorsConfigurationSource(CorsProperties properties) {
        this.configuration = new HostMatchingCorsConfiguration(properties.getAllowedOrigins());
        this.configuration.addAllowedHeader(CorsConfiguration.ALL);
        this.configuration.addAllowedMethod(CorsConfiguration.ALL);
        this.configuration.setAllowCredentials(true);
        this.configuration.setExposedHeaders(List.copyOf(properties.getExposedHeaders()));
        this.configuration.setMaxAge(PREFLIGHT_MAX_AGE_SECONDS);
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        return configuration;
    }

    private static final class HostMatchingCorsConfiguration extends CorsConfiguration {

        private final List<String> allowedHosts;

        private HostMatchingCorsConfiguration(List<String> allowedHosts) {
            this.allowedHosts = List.copyOf(allowedHosts);
        }

        @Override
        public String checkOrigin(String requestOrigin) {
            if (!StringUtils.hasText(requestOrigin)) {
                return null;
            }
            String host;
            try {
                host = URI.create(requestOrigin).getHost();
            } catch (IllegalArgumentException ex) {
                return null;
            }
            if (host == null) {
                return null;
            }
            for (String allowed : allowedHosts) {
                if (host.equalsIgnoreCase(allowed)) {
                    // Echo the origin back - required because credentials are allowed.
                    return requestOrigin;
                }
            }
            return null;
        }
    }
}
