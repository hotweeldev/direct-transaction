package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where beneficiary-name inquiry comes from: direct-integration's internal core-account
 * endpoint. Unlike direct-account's balance decoration this hop is load-bearing for the
 * transfer form - when it is off or down the inquiry endpoint answers 503 rather than
 * inventing a name the user would then confirm a transfer against.
 */
@ConfigurationProperties(prefix = "app.integration")
public class IntegrationProperties {

    /**
     * Off means: never call out, the inquiry endpoint answers 503 with a readable message.
     * The default is off so a local run without a direct-integration stack still boots.
     */
    private boolean enabled = false;

    /** direct-integration base URL serving /internal/v1/core. */
    private String baseUrl = "http://localhost:5243";

    /** Sent as X-Api-Key; identifies this service on the internal hop. */
    private String apiKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
