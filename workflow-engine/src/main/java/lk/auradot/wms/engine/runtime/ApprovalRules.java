package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.Outcomes;

import java.util.List;
import java.util.Optional;

/**
 * Decides a user-task node's outcome from the tasks of one activation (spec §4.2):
 * <ul>
 *   <li>SINGLE / ANY: the first completion decides;</li>
 *   <li>ALL: any non-APPROVE outcome decides at once; otherwise APPROVE when everyone approved;</li>
 *   <li>PERCENT(n): APPROVE once n% approved; REJECT once n% can no longer be reached.</li>
 * </ul>
 */
final class ApprovalRules {

    private ApprovalRules() {
    }

    static Optional<String> decide(ApprovalMode mode, Integer percent, List<Task> round, Task justCompleted) {
        long total = round.size();
        long completed = round.stream().filter(t -> t.status() == TaskStatus.COMPLETED).count();
        long approvals = round.stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED && Outcomes.APPROVE.equals(t.outcome())).count();
        return switch (mode) {
            case SINGLE, ANY -> Optional.of(justCompleted.outcome());
            case ALL -> !Outcomes.APPROVE.equals(justCompleted.outcome())
                    ? Optional.of(justCompleted.outcome())
                    : completed == total ? Optional.of(Outcomes.APPROVE) : Optional.empty();
            case PERCENT -> {
                long needed = (long) Math.ceil(percent * total / 100.0);
                long open = total - completed;
                if (approvals >= needed) {
                    yield Optional.of(Outcomes.APPROVE);
                }
                yield approvals + open < needed ? Optional.of(Outcomes.REJECT) : Optional.empty();
            }
        };
    }
}
