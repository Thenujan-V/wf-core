package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.ProcessCompiler;
import lk.auradot.wms.engine.definition.ProcessDefinition;

import java.time.Instant;
import java.util.List;

/** One engine wired to fakes; the clock starts on Monday 2026-09-28 09:00 UTC. */
final class Fixture {

    final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T09:00:00Z"));
    final FakeDirectory directory = new FakeDirectory();
    final ProcessEngine engine;

    Fixture() {
        this(WorkCalendar.calendarDays(), RetryPolicy.defaults());
    }

    Fixture(WorkCalendar calendar, RetryPolicy retry) {
        engine = new ProcessEngine(directory, calendar, clock, new SequentialIds(), retry);
    }

    CompiledProcess compile(ProcessDefinition definition) {
        return ProcessCompiler.compile(definition);
    }

    static List<Class<?>> eventTypes(List<EngineEvent> events) {
        return events.stream().<Class<?>>map(Object::getClass).toList();
    }
}
