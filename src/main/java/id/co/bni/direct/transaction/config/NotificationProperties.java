package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where notification events go once the relay drains them.
 *
 * <p>Separate from {@link ExecutionProperties} on purpose: notifications and execution
 * share the TRX_EVENT_OUTBOX table but NOT the topic, and conflating the two settings is
 * exactly the mistake that puts a TASK_APPROVED event in front of the execution consumer.
 * The relay routes per row by EVENT_TYPE, see {@code ExecutionOutboxRelay}.
 *
 * <p>Bound in every execution mode. In {@code sync} mode nothing publishes - the rows are
 * still written (they belong to the workflow commit) and simply sit NEW until a relay with
 * a broker drains them.
 */
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    /**
     * The frozen contract's topic name. Key = taskId, same as the execution topic, so one
     * task's notification events stay ordered on one partition.
     */
    private String topic = "direct.notification.events";

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
