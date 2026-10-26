package lk.auradot.wms.engine.definition;

/** One of the seven node types of spec §4.2. */
public sealed interface Node
        permits StartNode, EndNode, UserTaskNode, ExclusiveGatewayNode, ParallelGatewayNode, TimerNode, SystemActionNode {

    String id();

    String name();

    /** Nodes where a token pauses until a person or a job moves it on. */
    default boolean isWaitState() {
        return this instanceof UserTaskNode || this instanceof TimerNode || this instanceof SystemActionNode;
    }
}
