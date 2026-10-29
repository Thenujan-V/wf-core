package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.Assignment;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.Edge;
import lk.auradot.wms.engine.definition.EndNode;
import lk.auradot.wms.engine.definition.ExclusiveGatewayNode;
import lk.auradot.wms.engine.definition.Node;
import lk.auradot.wms.engine.definition.ParallelGatewayNode;
import lk.auradot.wms.engine.definition.ProcessDefinition;
import lk.auradot.wms.engine.definition.Sla;
import lk.auradot.wms.engine.definition.StartNode;
import lk.auradot.wms.engine.definition.SystemActionNode;
import lk.auradot.wms.engine.definition.TimerNode;
import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.expression.ConditionEvaluator;
import lk.auradot.wms.expression.Values;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow engine (spec §4.4). Every command moves tokens synchronously until each one waits at a user task,
 * timer or system action, or has ended; the caller persists the resulting state and drained events in one
 * transaction.
 */
public final class ProcessEngine {

    static final String SYSTEM_USER = "system";

    private final AssigneeResolver directory;
    private final WorkCalendar calendar;
    private final Clock clock;
    private final IdGenerator ids;
    private final RetryPolicy retry;

    public ProcessEngine(AssigneeResolver directory, WorkCalendar calendar, Clock clock, IdGenerator ids,
                         RetryPolicy retry) {
        this.directory = directory;
        this.calendar = calendar;
        this.clock = clock;
        this.ids = ids;
        this.retry = retry;
    }

    // ---------------------------------------------------------------- commands

    public InstanceState start(CompiledProcess process, String initiatorId, Map<String, Object> startData,
                               String businessKey) {
        Instant now = clock.instant();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("initiator", initiatorId);
        variables.put("requester", new LinkedHashMap<>(directory.profile(initiatorId)));
        variables.put("form", new LinkedHashMap<>(startData == null ? Map.of() : startData));
        ProcessDefinition definition = process.definition();
        InstanceState state = new InstanceState(ids.next(), definition.key(), definition.version(), initiatorId,
                businessKey, now, InstanceStatus.ACTIVE, null, null, variables, List.of(), List.of(), List.of(), List.of());
        state.record(new EngineEvent.InstanceStarted(initiatorId));
        Token token = state.addToken(new Token(ids.next(), process.start().id(), TokenStatus.ACTIVE, null));
        state.record(new EngineEvent.NodeEntered(token.nodeId(), token.id()));
        run(process, state, new ArrayDeque<>(List.of(token)));
        return state;
    }

    public void completeTask(CompiledProcess process, InstanceState state, String taskId, String userId,
                             String outcome, Map<String, Object> formData) {
        requireActive(state);
        Task task = openTask(state, taskId);
        if (task.assigneeId() == null) {
            throw new EngineException(EngineError.TASK_NOT_CLAIMED, task.candidates().isEmpty()
                    ? "This task has no assignee yet; an administrator must reassign it"
                    : "Claim the task before completing it");
        }
        if (!task.assigneeId().equals(userId)) {
            throw new EngineException(EngineError.NOT_ASSIGNEE, "Only the assignee can complete this task");
        }
        UserTaskNode node = (UserTaskNode) process.node(task.nodeId());
        if (!node.outcomes().contains(outcome)) {
            throw new EngineException(EngineError.INVALID_OUTCOME, "Unknown outcome '" + outcome + "'");
        }
        mergeForm(state, formData);
        finishTask(process, state, task, node, userId, outcome);
    }

    public void claimTask(CompiledProcess process, InstanceState state, String taskId, String userId) {
        requireActive(state);
        Task task = openTask(state, taskId);
        if (task.assigneeId() != null || task.candidates().isEmpty() || !isCandidate(task, userId)) {
            throw new EngineException(EngineError.NOT_CANDIDATE, "You cannot claim this task");
        }
        task.claim(userId);
        state.record(new EngineEvent.TaskClaimed(task.id(), userId));
    }

    public void unclaimTask(CompiledProcess process, InstanceState state, String taskId, String userId) {
        requireActive(state);
        Task task = openTask(state, taskId);
        if (task.status() != TaskStatus.CLAIMED || !userId.equals(task.assigneeId())) {
            throw new EngineException(EngineError.NOT_ASSIGNEE, "Only the user who claimed the task can release it");
        }
        task.unclaim();
        state.record(new EngineEvent.TaskUnclaimed(task.id(), userId));
    }

    public void delegateTask(CompiledProcess process, InstanceState state, String taskId, String userId,
                             String toUserId) {
        requireActive(state);
        Task task = openTask(state, taskId);
        if (!userId.equals(task.assigneeId())) {
            throw new EngineException(EngineError.NOT_ASSIGNEE, "Only the assignee can delegate this task");
        }
        if (toUserId == null || toUserId.isBlank() || toUserId.equals(userId)) {
            throw new EngineException(EngineError.INVALID_DELEGATE, "Choose another person to delegate to");
        }
        requireAssignable(state, task, toUserId);
        task.assign(toUserId);
        state.record(new EngineEvent.TaskDelegated(task.id(), userId, toUserId));
    }

    /** Administrator operation; also resolves unassigned tasks. */
    public void reassignTask(CompiledProcess process, InstanceState state, String taskId, String toUserId,
                             String actorId) {
        requireActive(state);
        Task task = openTask(state, taskId);
        if (toUserId == null || toUserId.isBlank()) {
            throw new EngineException(EngineError.INVALID_DELEGATE, "Choose the new assignee");
        }
        requireAssignable(state, task, toUserId);
        String from = task.assigneeId();
        task.assign(toUserId);
        state.record(new EngineEvent.TaskReassigned(task.id(), from, toUserId, actorId));
    }

    /** Runs a due job. Safe to call more than once: jobs that are no longer pending are ignored. */
    public void fireJob(CompiledProcess process, InstanceState state, String jobId) {
        Job job = state.job(jobId)
                .orElseThrow(() -> new EngineException(EngineError.JOB_NOT_FOUND, "Unknown job " + jobId));
        if (job.status() != JobStatus.PENDING || state.status() != InstanceStatus.ACTIVE) {
            return;
        }
        switch (job.type()) {
            case TIMER -> {
                job.done();
                Token token = state.token(job.tokenId());
                state.record(new EngineEvent.TimerFired(token.nodeId(), token.id()));
                continueFrom(process, state, token);
            }
            case SLA_REMINDER -> {
                job.done();
                Task task = state.task(job.taskId()).orElseThrow();
                if (task.isOpen()) {
                    state.record(new EngineEvent.TaskReminderDue(task.id(), task.assigneeId()));
                }
            }
            case SLA_BREACH -> {
                job.done();
                Task task = state.task(job.taskId()).orElseThrow();
                if (task.isOpen()) {
                    breach(process, state, task);
                }
            }
            case ASYNC_ACTION -> runAction(process, state, job);
        }
    }

    /** Administrator operation: a dead job is tried again now (a dead timer is released immediately). */
    public void retryJob(CompiledProcess process, InstanceState state, String jobId, String actorId) {
        requireActive(state);
        Job job = state.job(jobId)
                .orElseThrow(() -> new EngineException(EngineError.JOB_NOT_FOUND, "Unknown job " + jobId));
        if (job.status() != JobStatus.DEAD) {
            throw new EngineException(EngineError.JOB_NOT_DEAD, "Only failed jobs can be retried");
        }
        job.reset(clock.instant());
        state.record(new EngineEvent.JobRetried(job.id(), actorId));
    }

    public void cancel(CompiledProcess process, InstanceState state, String actorId) {
        cancel(process, state, actorId, null);
    }

    public void cancel(CompiledProcess process, InstanceState state, String actorId, String reason) {
        if (state.status() != InstanceStatus.ACTIVE && state.status() != InstanceStatus.SUSPENDED) {
            throw new EngineException(EngineError.INSTANCE_NOT_ACTIVE, "The instance has already ended");
        }
        Instant now = clock.instant();
        for (Task task : state.openTasks()) {
            task.cancel(now);
            state.record(new EngineEvent.TaskCancelled(task.id(),
                    reason == null ? "Instance cancelled" : "Instance cancelled: " + reason));
        }
        for (Job job : state.jobs()) {
            if (job.status() == JobStatus.PENDING || job.status() == JobStatus.SUSPENDED || job.status() == JobStatus.DEAD) {
                job.cancel();
            }
        }
        state.tokens().forEach(Token::end);
        state.finish(InstanceStatus.CANCELLED, now);
        state.record(new EngineEvent.InstanceCancelled(actorId, reason));
    }

    public void suspend(CompiledProcess process, InstanceState state, String actorId) {
        requireActive(state);
        state.jobs().stream().filter(j -> j.status() == JobStatus.PENDING).forEach(Job::suspend);
        state.setStatus(InstanceStatus.SUSPENDED);
        state.record(new EngineEvent.InstanceSuspended(actorId));
    }

    public void resume(CompiledProcess process, InstanceState state, String actorId) {
        if (state.status() != InstanceStatus.SUSPENDED) {
            throw new EngineException(EngineError.INSTANCE_NOT_ACTIVE, "Only a suspended instance can be resumed");
        }
        Instant now = clock.instant();
        state.jobs().stream().filter(j -> j.status() == JobStatus.SUSPENDED).forEach(j -> j.resume(now));
        state.setStatus(InstanceStatus.ACTIVE);
        state.record(new EngineEvent.InstanceResumed(actorId));
    }

    // ---------------------------------------------------------------- token movement

    private void run(CompiledProcess process, InstanceState state, Deque<Token> work) {
        while (!work.isEmpty()) {
            Token token = work.poll();
            if (token.status() != TokenStatus.ACTIVE) {
                continue;
            }
            Node node = process.node(token.nodeId());
            switch (node) {
                case StartNode start -> leaveVia(state, token, process.outgoing(start.id()).getFirst(), work);
                case EndNode end -> {
                    token.end();
                    if (end.endStatus() != null) {
                        state.setEndStatus(end.endStatus());
                    }
                }
                case UserTaskNode task -> {
                    token.waitHere();
                    activateUserTask(state, token, task);
                }
                case ExclusiveGatewayNode gateway -> leaveVia(state, token, choose(process, state, gateway), work);
                case ParallelGatewayNode parallel -> {
                    if (process.isSplit(parallel.id())) {
                        split(process, state, token, parallel, work);
                    } else {
                        join(process, state, token, parallel, work);
                    }
                }
                case TimerNode timer -> {
                    token.waitHere();
                    scheduleTimer(state, token, timer);
                }
                case SystemActionNode action -> {
                    token.waitHere();
                    state.addJob(Job.pending(ids.next(), JobType.ASYNC_ACTION, token.id(), null, clock.instant()));
                }
            }
        }
        completeIfFinished(state);
    }

    private void continueFrom(CompiledProcess process, InstanceState state, Token token) {
        Deque<Token> work = new ArrayDeque<>();
        leaveVia(state, token, process.outgoing(token.nodeId()).getFirst(), work);
        run(process, state, work);
    }

    private void leaveVia(InstanceState state, Token token, Edge edge, Deque<Token> work) {
        token.moveTo(edge.target());
        state.record(new EngineEvent.NodeEntered(edge.target(), token.id()));
        work.add(token);
    }

    private Edge choose(CompiledProcess process, InstanceState state, ExclusiveGatewayNode gateway) {
        Map<String, Object> context = new LinkedHashMap<>(state.variables());
        context.put("now", clock.instant().toString());
        Edge fallback = null;
        Edge chosen = null;
        for (Edge edge : process.outgoing(gateway.id())) {
            if (edge.defaultFlow()) {
                fallback = edge;
            } else if (chosen == null && ConditionEvaluator.evaluate(edge.condition(), context)) {
                chosen = edge;
            }
        }
        Edge result = chosen != null ? chosen : fallback;
        state.record(new EngineEvent.GatewayRouted(gateway.id(), result.id()));
        return result;
    }

    private void split(CompiledProcess process, InstanceState state, Token token, ParallelGatewayNode split,
                       Deque<Token> work) {
        List<Edge> branches = process.outgoing(split.id());
        ForkScope scope = state.addScope(new ForkScope(ids.next(), token.scopeId(), split.id(),
                process.joinFor(split.id()), branches.size(), 0));
        token.end();
        for (Edge edge : branches) {
            Token branch = state.addToken(new Token(ids.next(), edge.target(), TokenStatus.ACTIVE, scope.id()));
            state.record(new EngineEvent.NodeEntered(edge.target(), branch.id()));
            work.add(branch);
        }
    }

    private void join(CompiledProcess process, InstanceState state, Token token, ParallelGatewayNode join,
                      Deque<Token> work) {
        ForkScope scope = state.scope(token.scopeId());
        if (scope == null || !scope.joinNodeId().equals(join.id())) {
            throw new IllegalStateException("Token " + token.id() + " reached join " + join.id() + " outside its fork");
        }
        token.end();
        scope.arrive();
        if (scope.complete()) {
            Token merged = state.addToken(new Token(ids.next(), join.id(), TokenStatus.ACTIVE, scope.parentScopeId()));
            leaveVia(state, merged, process.outgoing(join.id()).getFirst(), work);
        }
    }

    private void completeIfFinished(InstanceState state) {
        if (state.status() != InstanceStatus.ACTIVE) {
            return;
        }
        boolean running = state.tokens().stream().anyMatch(t -> t.status() != TokenStatus.ENDED);
        if (!running) {
            state.finish(InstanceStatus.COMPLETED, clock.instant());
            state.record(new EngineEvent.InstanceCompleted(state.endStatus()));
        }
    }

    // ---------------------------------------------------------------- user tasks

    private record Resolution(List<String> users, String candidate, String unassignedReason) {
    }

    private void activateUserTask(InstanceState state, Token token, UserTaskNode node) {
        String activationId = ids.next();
        Instant now = clock.instant();
        Instant dueAt = node.sla() == null ? null : dueAt(now, node.sla());
        Resolution resolution = resolve(node, state);
        if (resolution.unassignedReason() != null) {
            createTask(state, token, node, activationId, null, List.of(), resolution.unassignedReason(), now, dueAt);
        } else if (resolution.candidate() != null) {
            createTask(state, token, node, activationId, null, List.of(resolution.candidate()), null, now, dueAt);
        } else {
            for (String user : resolution.users()) {
                createTask(state, token, node, activationId, user, List.of(), null, now, dueAt);
            }
        }
    }

    private Resolution resolve(UserTaskNode node, InstanceState state) {
        boolean single = node.approvalMode() == ApprovalMode.SINGLE;
        return switch (node.assignment()) {
            case Assignment.User user -> directory.isActiveUser(user.userId())
                    ? new Resolution(List.of(user.userId()), null, null)
                    : new Resolution(List.of(), null, "User '" + user.userId() + "' is not an active user");
            case Assignment.Group group -> pool(directory.groupMembers(group.groupId()), "group:" + group.groupId(),
                    single, "Group '" + group.groupId() + "' has no members");
            case Assignment.Role role -> pool(directory.roleMembers(role.role()), "role:" + role.role(),
                    single, "Nobody has the role '" + role.role() + "'");
            case Assignment.InitiatorManager manager -> {
                List<String> chain = directory.managers(state.initiatorId(), manager.levels());
                if (chain.isEmpty()) {
                    yield new Resolution(List.of(), null, "The initiator has no manager");
                }
                yield directory.isActiveUser(chain.getLast())
                        ? new Resolution(List.of(chain.getLast()), null, null)
                        : new Resolution(List.of(), null, "Manager '" + chain.getLast() + "' is not an active user");
            }
            case Assignment.VariableUser variable -> {
                Object value = Values.resolve(state.variables(), variable.variable());
                yield value instanceof String user && !user.isBlank() && directory.isActiveUser(user)
                        ? new Resolution(List.of(user), null, null)
                        : new Resolution(List.of(), null, "Variable '" + variable.variable() + "' does not hold an active user");
            }
        };
    }

    /** SINGLE: one claimable task for the pool; multi-approver: one task per distinct member. */
    private static Resolution pool(List<String> members, String candidate, boolean single, String emptyReason) {
        if (members.isEmpty()) {
            return new Resolution(List.of(), null, emptyReason);
        }
        return single
                ? new Resolution(List.of(), candidate, null)
                : new Resolution(List.copyOf(new LinkedHashSet<>(members)), null, null);
    }

    private void createTask(InstanceState state, Token token, UserTaskNode node, String activationId, String assignee,
                            List<String> candidates, String unassignedReason, Instant now, Instant dueAt) {
        Task task = state.addTask(new Task(ids.next(), token.id(), activationId, node.id(), node.name(),
                TaskStatus.OPEN, assignee, candidates, null, null, unassignedReason, now, dueAt, null));
        state.record(new EngineEvent.TaskCreated(task.id(), node.id(), assignee, candidates, dueAt));
        if (unassignedReason != null) {
            state.record(new EngineEvent.TaskUnassignable(task.id(), node.id(), unassignedReason));
        }
        Sla sla = node.sla();
        if (sla != null) {
            if (sla.reminderPercent() != null) {
                Duration span = Duration.between(now, dueAt);
                Instant remindAt = now.plus(span.multipliedBy(sla.reminderPercent()).dividedBy(100));
                state.addJob(Job.pending(ids.next(), JobType.SLA_REMINDER, token.id(), task.id(), remindAt));
            }
            state.addJob(Job.pending(ids.next(), JobType.SLA_BREACH, token.id(), task.id(), dueAt));
        }
    }

    private Instant dueAt(Instant now, Sla sla) {
        return sla.businessDays()
                ? calendar.addWorkingDays(now, (int) sla.dueIn().toDays())
                : now.plus(sla.dueIn());
    }

    private void finishTask(CompiledProcess process, InstanceState state, Task task, UserTaskNode node,
                            String userId, String outcome) {
        Instant now = clock.instant();
        task.complete(userId, outcome, now);
        cancelJobsOf(state, task);
        state.record(new EngineEvent.TaskCompleted(task.id(), node.id(), userId, outcome));
        List<Task> round = state.tasksOfActivation(task.activationId());
        Optional<String> decision = ApprovalRules.decide(node.approvalMode(), node.percent(), round, task);
        if (decision.isEmpty()) {
            return;
        }
        for (Task other : round) {
            if (other.isOpen()) {
                other.cancel(now);
                cancelJobsOf(state, other);
                state.record(new EngineEvent.TaskCancelled(other.id(), "Decided by other approvers"));
            }
        }
        recordNodeOutcome(state, node.id(), decision.get());
        state.record(new EngineEvent.NodeDecided(node.id(), decision.get()));
        Token token = state.token(task.tokenId());
        Edge edge = process.outgoing(node.id()).stream()
                .filter(e -> decision.get().equals(e.outcome())).findFirst().orElseThrow();
        Deque<Token> work = new ArrayDeque<>();
        leaveVia(state, token, edge, work);
        run(process, state, work);
    }

    private void breach(CompiledProcess process, InstanceState state, Task task) {
        UserTaskNode node = (UserTaskNode) process.node(task.nodeId());
        BreachAction action = node.sla().onBreach();
        switch (action) {
            case NOTIFY -> state.record(new EngineEvent.TaskOverdue(task.id(), task.assigneeId(), BreachAction.NOTIFY));
            case REASSIGN_TO_MANAGER -> {
                List<String> managers = task.assigneeId() == null ? List.of() : directory.managers(task.assigneeId(), 1);
                if (managers.isEmpty() || !directory.isActiveUser(managers.getFirst())
                        || holdsTaskInRound(state, task, managers.getFirst())) {
                    state.record(new EngineEvent.TaskOverdue(task.id(), task.assigneeId(), BreachAction.NOTIFY));
                } else {
                    String from = task.assigneeId();
                    task.assign(managers.getFirst());
                    state.record(new EngineEvent.TaskOverdue(task.id(), from, BreachAction.REASSIGN_TO_MANAGER));
                    state.record(new EngineEvent.TaskReassigned(task.id(), from, managers.getFirst(), SYSTEM_USER));
                }
            }
            case AUTO_COMPLETE -> {
                state.record(new EngineEvent.TaskOverdue(task.id(), task.assigneeId(), BreachAction.AUTO_COMPLETE));
                finishTask(process, state, task, node, SYSTEM_USER, node.sla().autoOutcome());
            }
        }
    }

    private boolean isCandidate(Task task, String userId) {
        for (String candidate : task.candidates()) {
            if (candidate.startsWith("group:") && directory.groupMembers(candidate.substring(6)).contains(userId)) {
                return true;
            }
            if (candidate.startsWith("role:") && directory.roleMembers(candidate.substring(5)).contains(userId)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- timers and actions

    private void scheduleTimer(InstanceState state, Token token, TimerNode timer) {
        Instant now = clock.instant();
        if (timer.duration() != null) {
            state.addJob(Job.pending(ids.next(), JobType.TIMER, token.id(), null, now.plus(timer.duration())));
            return;
        }
        Optional<Instant> at = Values.toInstant(Values.resolve(state.variables(), timer.dueDateVariable()));
        Job job = state.addJob(Job.pending(ids.next(), JobType.TIMER, token.id(), null, at.orElse(now)));
        if (at.isEmpty()) {
            job.dead("Timer date variable '" + timer.dueDateVariable() + "' is missing or not an ISO date");
            state.record(new EngineEvent.JobDead(job.id(), job.lastError()));
        }
    }

    /** SET_VARIABLE runs inside the engine; external actions must go through actionRequest / actionSucceeded. */
    private void runAction(CompiledProcess process, InstanceState state, Job job) {
        SystemActionNode node = actionNode(process, state, job);
        if (node.type() != ActionType.SET_VARIABLE) {
            throw new EngineException(EngineError.EXTERNAL_ACTION,
                    "Run this job with actionRequest, then report actionSucceeded or actionFailed");
        }
        job.attempt();
        setVariable(state, (String) node.config().get("variable"), node.config().get("value"));
        succeed(process, state, job, node);
    }

    /**
     * Describes a due email or webhook without changing any state, so the caller can perform it outside the
     * transaction and then report the result. Empty when the job has nothing external left to run.
     */
    public Optional<ActionRequest> actionRequest(CompiledProcess process, InstanceState state, String jobId) {
        Job job = requireJob(state, jobId);
        if (!isExternalActionDue(process, state, job)) {
            return Optional.empty();
        }
        SystemActionNode node = actionNode(process, state, job);
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) snapshot(state.variables());
        return Optional.of(new ActionRequest(job.id(), node.id(), node.type(), node.config(), variables));
    }

    /** The external action was performed; continues the process. Repeated reports are ignored. */
    public void actionSucceeded(CompiledProcess process, InstanceState state, String jobId) {
        Job job = requireJob(state, jobId);
        if (!isExternalActionDue(process, state, job)) {
            return;
        }
        job.attempt();
        succeed(process, state, job, actionNode(process, state, job));
    }

    /** The external action failed; schedules a retry with backoff or marks the job dead. Stale reports are ignored. */
    public void actionFailed(CompiledProcess process, InstanceState state, String jobId, String error, boolean retryable) {
        Job job = requireJob(state, jobId);
        if (!isExternalActionDue(process, state, job)) {
            return;
        }
        SystemActionNode node = actionNode(process, state, job);
        job.attempt();
        if (retryable && job.attempts() < retry.maxAttempts()) {
            Instant retryAt = clock.instant().plus(retry.backoffAfter(job.attempts()));
            job.retryAt(retryAt, error);
            state.record(new EngineEvent.ActionFailed(node.id(), node.type(), error, retryAt));
        } else {
            job.dead(error);
            state.record(new EngineEvent.JobDead(job.id(), error));
        }
    }

    private void succeed(CompiledProcess process, InstanceState state, Job job, SystemActionNode node) {
        job.done();
        state.record(new EngineEvent.ActionExecuted(node.id(), node.type()));
        continueFrom(process, state, state.token(job.tokenId()));
    }

    private static boolean isExternalActionDue(CompiledProcess process, InstanceState state, Job job) {
        return job.type() == JobType.ASYNC_ACTION && job.status() == JobStatus.PENDING
                && state.status() == InstanceStatus.ACTIVE
                && actionNode(process, state, job).type() != ActionType.SET_VARIABLE;
    }

    private static SystemActionNode actionNode(CompiledProcess process, InstanceState state, Job job) {
        return (SystemActionNode) process.node(state.token(job.tokenId()).nodeId());
    }

    private static Job requireJob(InstanceState state, String jobId) {
        return state.job(jobId)
                .orElseThrow(() -> new EngineException(EngineError.JOB_NOT_FOUND, "Unknown job " + jobId));
    }

    /** A deep, read-only copy of a variable tree. */
    private static Object snapshot(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, snapshot(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(list.stream().map(ProcessEngine::snapshot).toList()));
        }
        return value;
    }

    // ---------------------------------------------------------------- helpers

    private static void requireActive(InstanceState state) {
        if (state.status() != InstanceStatus.ACTIVE) {
            throw new EngineException(EngineError.INSTANCE_NOT_ACTIVE, "The instance is " + state.status());
        }
    }

    private static Task openTask(InstanceState state, String taskId) {
        Task task = state.task(taskId)
                .orElseThrow(() -> new EngineException(EngineError.TASK_NOT_FOUND, "Unknown task " + taskId));
        if (!task.isOpen()) {
            throw new EngineException(EngineError.TASK_NOT_OPEN, "The task is " + task.status());
        }
        return task;
    }

    /**
     * In a multi-approver round every vote must come from a different person, so a round task may never move to
     * someone who already holds (or completed) another task of the same round.
     */
    private static boolean holdsTaskInRound(InstanceState state, Task task, String userId) {
        return state.tasksOfActivation(task.activationId()).stream()
                .filter(other -> other != task && other.status() != TaskStatus.CANCELLED)
                .anyMatch(other -> userId.equals(other.assigneeId()) || userId.equals(other.completedBy()));
    }

    private void requireAssignable(InstanceState state, Task task, String userId) {
        if (!directory.isActiveUser(userId)) {
            throw new EngineException(EngineError.INVALID_DELEGATE, "'" + userId + "' is not an active user");
        }
        if (holdsTaskInRound(state, task, userId)) {
            throw new EngineException(EngineError.INVALID_DELEGATE,
                    "That person already has a vote in this approval; choose someone else");
        }
    }

    private static void cancelJobsOf(InstanceState state, Task task) {
        state.jobsOfTask(task.id()).stream().filter(j -> j.status() == JobStatus.PENDING).forEach(Job::cancel);
    }

    private static void mergeForm(InstanceState state, Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return;
        }
        mutableChild(state.variables(), "form").putAll(formData);
    }

    private static void recordNodeOutcome(InstanceState state, String nodeId, String outcome) {
        Map<String, Object> nodes = mutableChild(state.variables(), "nodes");
        Map<String, Object> entry = mutableChild(nodes, nodeId);
        entry.put("outcome", outcome);
    }

    private static void setVariable(InstanceState state, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = state.variables();
        for (int i = 0; i < segments.length - 1; i++) {
            current = mutableChild(current, segments[i]);
        }
        current.put(segments[segments.length - 1], value);
    }

    /** Returns parent[key] as a mutable map, replacing a missing or non-map value with a new map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableChild(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        Map<String, Object> child = existing instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        parent.put(key, child);
        return child;
    }
}
