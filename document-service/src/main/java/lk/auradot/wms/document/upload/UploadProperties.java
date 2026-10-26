package lk.auradot.wms.document.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** How long an upload may wait for its confirm; after that it is purged with its object. */
@ConfigurationProperties("wms.document.uploads")
public record UploadProperties(@DefaultValue("PT24H") Duration confirmWindow, @DefaultValue("true") boolean purgeEnabled,
                               @DefaultValue("PT1H") Duration purgeInterval) {
}
