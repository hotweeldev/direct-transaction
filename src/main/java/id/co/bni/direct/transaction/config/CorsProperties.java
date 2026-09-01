package id.co.bni.direct.transaction.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins allowed to call this service, and the headers they may read back. */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Allowed origin <b>hosts</b>, without scheme or port - the matcher compares
     * {@code URI.getHost()} only, the same way spine-backoffice does.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /** Response headers exposed to the browser. */
    private List<String> exposedHeaders = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getExposedHeaders() {
        return exposedHeaders;
    }

    public void setExposedHeaders(List<String> exposedHeaders) {
        this.exposedHeaders = exposedHeaders;
    }
}
