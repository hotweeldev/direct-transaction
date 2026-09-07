package id.co.bni.direct.transaction.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The Kafka half of the execution pipeline, present only when
 * {@code app.execution.mode=kafka} - a laptop without a broker never builds a producer
 * or wakes a scheduler.
 *
 * <p>{@code @EnableScheduling} moved OUT of this class to {@code SchedulingConfig}: it is
 * needed in every execution mode now that the daily limit rebuild is scheduled, and this
 * configuration only exists in kafka mode. Historically it lived here for the same reason it lived on
 * direct-bankmodule's UmasProvisioningScheduler: scheduling is only needed for the
 * outbox relay, so it is only switched on alongside it.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.execution", name = "mode", havingValue = "kafka")
public class KafkaExecutionConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaExecutionConfig.class);

    /**
     * String/String producer for the two execution topics. Built here (rather than using
     * Boot's {@code KafkaTemplate<Object, Object>}) so the relay and consumer can inject
     * a precisely-typed template - the payloads are pre-serialized JSON strings, the key
     * is the taskId.
     */
    @Bean
    public ProducerFactory<String, String> executionProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null),
                new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> executionKafkaTemplate(
            ProducerFactory<String, String> executionProducerFactory) {
        return new KafkaTemplate<>(executionProducerFactory);
    }

    /**
     * Listener error handling: a few fixed retries, then the failed record goes to the
     * dead-letter topic ({@code <requested>.DLT}, the recoverer's default naming) with
     * the exception in the record headers. This path is for INFRASTRUCTURE errors only -
     * an unparsable payload, the database gone away mid-claim. Business outcomes
     * (FAILED / UNKNOWN) are normal returns of the execution seam and never throw, so
     * they never retry and never dead-letter.
     */
    @Bean
    public CommonErrorHandler kafkaExecutionErrorHandler(
            KafkaTemplate<String, String> executionKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(executionKafkaTemplate);
        // 3 retries, 1s apart, then dead-letter. The claim's VERSION guard makes a
        // retry of a half-executed record a no-op, so retrying is always safe.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }
}
