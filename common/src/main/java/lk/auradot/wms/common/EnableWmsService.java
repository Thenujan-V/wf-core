package lk.auradot.wms.common;

import lk.auradot.wms.common.security.WmsSecurityConfiguration;
import lk.auradot.wms.common.web.WmsWebConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Applies the shared WMS servlet-service setup: JWT security, correlation ids, ProblemDetail errors. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({WmsSecurityConfiguration.class, WmsWebConfiguration.class})
public @interface EnableWmsService {
}
