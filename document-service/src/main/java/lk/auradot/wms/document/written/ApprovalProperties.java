package lk.auradot.wms.document.written;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("wms.document.approvals")
public record ApprovalProperties(@DefaultValue("true") boolean settleEnabled,
                                 @DefaultValue("PT30S") Duration settleInterval,
                                 @DefaultValue("PT10M") Duration staleAfter) {
}
