package id.co.bni.direct.transaction.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for {@link CorrelationFilter}. */
@ConfigurationProperties(prefix = "app.correlation")
public class CorrelationProperties {

    /**
     * Header carrying the id between services. Must stay {@code X-Correlation-Id}: that is
     * what the hw-auth-shared starter reads in the Spine services, and an id only joins
     * two logs if both ends agree on the header.
     */
    private String headerName = "X-Correlation-Id";

    /** Name written into the inbound log line, so one log stream can hold many services. */
    private String serviceName = "direct-transaction";

    /** Path prefixes that produce no inbound log line. Probes and docs, by default. */
    private List<String> excludedPaths = List.of("/actuator", "/swagger-ui", "/v3/api-docs");

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
