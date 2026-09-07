package id.co.bni.direct.transaction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling, on unconditionally.
 *
 * <p>It used to be switched on only by {@link KafkaExecutionConfig}, which exists only when
 * {@code app.execution.mode=kafka}. That was harmless while the outbox relay was the one
 * scheduled thing here, since the relay has nothing to do in sync mode anyway - but it made
 * the service quietly incapable of running any OTHER schedule in its default deployment,
 * which is a trap rather than a design. Scheduling is now on in every mode and each
 * component decides for itself whether it has work.
 *
 * <p>Batch jobs themselves do NOT belong here: they live in direct-worker, so a long
 * rebuild can never compete with a customer's transfer for a thread or a connection.
 *
 * <p>Each scheduled component still guards itself with its own enabled flag, so turning
 * scheduling on here does not start anything a deployment has switched off.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
