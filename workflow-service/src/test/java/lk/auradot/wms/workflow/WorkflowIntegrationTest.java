package lk.auradot.wms.workflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {"wms.workflow.jobs.enabled=false", "wms.outbox.enabled=false",
        "wms.workflow.stats.cache-ttl=PT0S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestWorkflowConfiguration.class})
public @interface WorkflowIntegrationTest {
}
