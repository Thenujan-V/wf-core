package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.runtime.EngineException;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.workflow.definition.CompiledProcesses;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import lk.auradot.wms.workflow.definition.StoredDefinition;
import lk.auradot.wms.workflow.directory.DirectoryUnavailableException;
import lk.auradot.wms.workflow.forms.FormSubmissions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs engine commands the way spec §4.4 describes: load, command, save (with history and outbox) in one
 * transaction; on a concurrent update the whole thing is retried on fresh state, up to three attempts.
 */
@Service
public class WorkflowCommands {

    public static final String SYSTEM = "system";
    static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate tx;
    private final InstanceStore instances;
    private final DefinitionStore definitions;
    private final CompiledProcesses processes;
    private final EventRecorder recorder;
    private final ProcessEngine engine;
    private final FormSubmissions submissions;

    public WorkflowCommands(TransactionTemplate tx, InstanceStore instances, DefinitionStore definitions,
                            CompiledProcesses processes, EventRecorder recorder, ProcessEngine engine,
                            FormSubmissions submissions) {
        this.submissions = submissions;
        this.tx = tx;
        this.instances = instances;
        this.definitions = definitions;
        this.processes = processes;
        this.recorder = recorder;
        this.engine = engine;
    }

    /** The instance a start resolved to, and whether this call created it or replayed an earlier start. */
    public record Started(String id, boolean created) {
    }

    public String start(String processKey, String userId, Map<String, Object> data, String businessKey) {
        return startOnce(processKey, userId, data, businessKey).id();
    }

    /**
     * A business key names one request: starting again with the same key (a client retry, a double submit) returns
     * the instance it already started instead of a duplicate. The same key for another process or another initiator
     * is a conflict, not a replay.
     */
    public Started startOnce(String processKey, String userId, Map<String, Object> data, String businessKey) {
        if (businessKey != null) {
            Optional<Started> replay = existing(processKey, userId, businessKey);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        try {
            return new Started(create(processKey, userId, data, businessKey), true);
        } catch (DuplicateKeyException e) {
            // a concurrent start with the same key won the unique index
            return existing(processKey, userId, businessKey).orElseThrow(() -> e);
        }
    }

    private Optional<Started> existing(String processKey, String userId, String businessKey) {
        return instances.findByBusinessKey(businessKey).map(found -> {
            if (!found.processKey().equals(processKey) || !Objects.equals(found.initiatorId(), userId)) {
                throw ApiException.conflict("WF_BUSINESS_KEY_CONFLICT",
                        "Business key '" + businessKey + "' already belongs to another request");
            }
            return new Started(found.id(), false);
        });
    }

    private String create(String processKey, String userId, Map<String, Object> data, String businessKey) {
        StoredDefinition definition = definitions.latestPublished(processKey).orElseThrow(
                () -> ApiException.notFound("WF_PROCESS_NOT_FOUND", "No published process '" + processKey + "'"));
        CompiledProcess process = processes.get(definition.id());
        // Outside the transaction: form-service validates and stores the start form first (spec §5).
        Map<String, Object> accepted = submissions.acceptStart(definition.id(), process.start().id(), processKey, data, userId);
        List<String> files = submissions.submittedFiles(definition.id(), process.start().id(), accepted);
        return inTransaction(() -> {
            InstanceState state = engine.start(process, userId, accepted, businessKey);
            instances.insert(definition.id(), state);
            recorder.record(process, state, state.drainEvents(), userId);
            recorder.filesSubmitted(state, files, userId);
            return state.id();
        });
    }

    public void onInstance(String instanceId, String actorId, BiConsumer<CompiledProcess, InstanceState> command) {
        for (int attempt = 1; ; attempt++) {
            try {
                inTransaction(() -> {
                    LoadedInstance loaded = load(instanceId);
                    CompiledProcess process = processes.get(loaded.definitionId());
                    command.accept(process, loaded.state());
                    instances.update(loaded);
                    recorder.record(process, loaded.state(), loaded.state().drainEvents(), actorId);
                    return null;
                });
                return;
            } catch (OptimisticConflictException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ApiException.conflict("WF_CONCURRENT_UPDATE",
                            "Someone else changed this request at the same time; please try again");
                }
            }
        }
    }

    public void onTask(String taskId, String actorId, BiConsumer<CompiledProcess, InstanceState> command) {
        String instanceId = instances.instanceOfTask(taskId)
                .orElseThrow(() -> ApiException.notFound("WF_TASK_NOT_FOUND", "Unknown task " + taskId));
        onInstance(instanceId, actorId, command);
    }

    public void onJob(String jobId, String actorId, BiConsumer<CompiledProcess, InstanceState> command) {
        String instanceId = instances.instanceOfJob(jobId)
                .orElseThrow(() -> ApiException.notFound("WF_JOB_NOT_FOUND", "Unknown job " + jobId));
        onInstance(instanceId, actorId, command);
    }

    /** Read-only access to the current state (nothing is saved). */
    public <T> T read(String instanceId, BiFunction<CompiledProcess, InstanceState, T> query) {
        return inTransaction(() -> {
            LoadedInstance loaded = load(instanceId);
            return query.apply(processes.get(loaded.definitionId()), loaded.state());
        });
    }

    /** Read-only access to the loaded instance, including the definition it runs on. */
    public <T> T readInstance(String instanceId, Function<LoadedInstance, T> query) {
        return inTransaction(() -> query.apply(load(instanceId)));
    }

    private LoadedInstance load(String instanceId) {
        return instances.load(instanceId)
                .orElseThrow(() -> ApiException.notFound("WF_INSTANCE_NOT_FOUND", "Unknown request " + instanceId));
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return tx.execute(status -> work.get());
        } catch (EngineException e) {
            throw toApi(e);
        } catch (DirectoryUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WF_DIRECTORY_UNAVAILABLE",
                    "The user directory is unavailable; please try again shortly");
        }
    }

    public static ApiException toApi(EngineException e) {
        HttpStatus status = switch (e.error()) {
            case TASK_NOT_FOUND, JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_ASSIGNEE, NOT_CANDIDATE -> HttpStatus.FORBIDDEN;
            case INVALID_OUTCOME, INVALID_DELEGATE -> HttpStatus.BAD_REQUEST;
            case EXTERNAL_ACTION -> HttpStatus.INTERNAL_SERVER_ERROR;
            case TASK_NOT_OPEN, TASK_NOT_CLAIMED, INSTANCE_NOT_ACTIVE, JOB_NOT_DEAD -> HttpStatus.CONFLICT;
        };
        return new ApiException(status, "WF_" + e.error().name(), e.getMessage());
    }
}
