package lk.auradot.wms.engine.definition;

/** Who gets a user task (spec §4.2). */
public sealed interface Assignment {

    record User(String userId) implements Assignment {
    }

    record Group(String groupId) implements Assignment {
    }

    record Role(String role) implements Assignment {
    }

    /** The initiator's manager {@code levels} up (1 = direct manager). */
    record InitiatorManager(int levels) implements Assignment {
    }

    /** The user id held in a variable, e.g. {@code form.approver}. */
    record VariableUser(String variable) implements Assignment {
    }

    /** Strategies that always name exactly one person, so multi-approver modes make no sense with them. */
    default boolean isSinglePerson() {
        return this instanceof User || this instanceof InitiatorManager || this instanceof VariableUser;
    }
}
