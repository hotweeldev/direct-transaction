package id.co.bni.direct.transaction.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The wire conventions every BNI Direct / Spine service shares: camelCase names, nulls
 * serialized rather than omitted, ISO-8601 dates rather than epoch numbers, and unknown
 * request properties ignored.
 *
 * <p>Nulls are included on purpose. A field that disappears when null forces every
 * consumer to distinguish "absent" from "null", and the MFEs do not.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer transactionJacksonCustomizer() {
        return builder -> builder
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
