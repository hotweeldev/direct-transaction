package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link ExecutionProperties} in BOTH modes: the workflow services read
 * {@code app.execution.mode} to decide whether a completed release lands
 * READY_TO_EXECUTE (sync, execute in-request) or QUEUED (kafka, outbox row). The Kafka
 * beans themselves live in {@link KafkaExecutionConfig}, conditional on mode=kafka.
 */
@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfig {
}
