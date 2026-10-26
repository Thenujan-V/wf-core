package lk.auradot.wms.document.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    public static final String TEXT_QUEUE = "document-service.text-extraction";
    public static final String VERSION_UPLOADED = "document.version.uploaded";
    public static final String CHECKOUT_FORCED = "document.checkout.forced";
    public static final String DOCUMENT_DELETED = "document.deleted";
    public static final String DOCUMENT_LINKED = "document.linked";
    public static final String FOLDER_SHARED = "document.folder.shared";
    public static final String FILES_SUBMITTED = "workflow.files.submitted";
    public static final String REQUEST_FILES_QUEUE = "document-service.request-files";
    public static final String INSTANCE_COMPLETED = "workflow.instance.completed";
    public static final String INSTANCE_CANCELLED = "workflow.instance.cancelled";
    public static final String APPROVALS_QUEUE = "document-service.approvals";
    public static final String DOCUMENT_RETURNED = "document.returned";
    public static final String DOCUMENT_APPROVED = "document.approved";
    public static final String APPROVAL_RELEASED = "document.approval.released";

    @Bean
    Queue textExtractionQueue() {
        return new Queue(TEXT_QUEUE, true);
    }

    @Bean
    Binding textExtractionBinding(Queue textExtractionQueue, TopicExchange wmsEvents) {
        return BindingBuilder.bind(textExtractionQueue).to(wmsEvents).with(VERSION_UPLOADED);
    }

    @Bean
    Queue requestFilesQueue() {
        return new Queue(REQUEST_FILES_QUEUE, true);
    }

    @Bean
    Binding requestFilesBinding(Queue requestFilesQueue, TopicExchange wmsEvents) {
        return BindingBuilder.bind(requestFilesQueue).to(wmsEvents).with(FILES_SUBMITTED);
    }

    @Bean
    Queue approvalsQueue() {
        return new Queue(APPROVALS_QUEUE, true);
    }

    @Bean
    Binding approvalsCompletedBinding(Queue approvalsQueue, TopicExchange wmsEvents) {
        return BindingBuilder.bind(approvalsQueue).to(wmsEvents).with(INSTANCE_COMPLETED);
    }

    @Bean
    Binding approvalsCancelledBinding(Queue approvalsQueue, TopicExchange wmsEvents) {
        return BindingBuilder.bind(approvalsQueue).to(wmsEvents).with(INSTANCE_CANCELLED);
    }
}
