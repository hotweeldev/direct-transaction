package id.co.bni.direct.transaction.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger document metadata. The UI is switched off in {@code application-prod.yml} rather
 * than here, so the document itself stays available to tooling that asks for it directly.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BNI Direct Transaction API")
                .description("Corporate transaction workflow APIs for BNI Direct.")
                .version("v1"));
    }
}
