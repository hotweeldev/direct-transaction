package id.co.bni.direct.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.integration.AccountNameClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the two outbound hops, shaped like direct-admin's
 * {@code UmasIntegrationConfig}: properties bound here, the clients plain classes built by
 * this configuration rather than components, so tests construct them with whatever
 * properties they need.
 */
@Configuration
@EnableConfigurationProperties({IntegrationProperties.class, AuthenticatorProperties.class,
        TransferTypeProperties.class})
public class IntegrationConfig {

    @Bean
    public AccountNameClient accountNameClient(IntegrationProperties properties,
                                               ObjectMapper objectMapper) {
        return new AccountNameClient(properties, objectMapper);
    }

    @Bean
    public CoreTransferClient coreTransferClient(IntegrationProperties properties,
                                                 ObjectMapper objectMapper) {
        return new CoreTransferClient(properties, objectMapper);
    }

    @Bean
    public UmasAuthenticatorClient umasAuthenticatorClient(AuthenticatorProperties properties,
                                                           ObjectMapper objectMapper) {
        return new UmasAuthenticatorClient(properties, objectMapper);
    }
}
