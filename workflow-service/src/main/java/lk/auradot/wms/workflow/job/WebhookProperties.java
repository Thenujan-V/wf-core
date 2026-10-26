package lk.auradot.wms.workflow.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("wms.workflow.webhooks")
public record WebhookProperties(@DefaultValue("5s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout,
                                @DefaultValue("false") boolean allowPrivateNetworks) {
}
