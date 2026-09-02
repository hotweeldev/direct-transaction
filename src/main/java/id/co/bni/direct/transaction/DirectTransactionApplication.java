package id.co.bni.direct.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Corporate transaction workflow engine for BNI Direct. */
@SpringBootApplication
// Same as direct-account: CorsProperties and CorrelationProperties are only
// @ConfigurationProperties, nothing @Enable-s them, so scan the package.
@ConfigurationPropertiesScan
public class DirectTransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DirectTransactionApplication.class, args);
    }
}
