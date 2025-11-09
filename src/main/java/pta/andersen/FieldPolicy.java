package pta.andersen;

import java.util.Set;
import java.util.function.BiConsumer;

import pascal.taie.analysis.pta.core.heap.Obj;
import pta.andersen.ModularAndersenSolver.Graph;
import pta.andersen.ModularAndersenSolver.Node;
import pta.andersen.ModularAndersenSolver.VarNode;

/**
 * Strategy interface for field-related behaviour. Implementations can choose to
 * react to bases being registered for object field accesses and points-to sets
 * being updated for variable nodes.
 */
public interface FieldPolicy {

    /**
     * Notify that {@code base} participates in an instance field access. The
     * default implementation does nothing, but a field-sensitive variant could
     * track base-object pairs.
     */
    default void registerBase(VarNode base) {
        // no-op for field-insensitive policy
    }

    /**
     * Hook invoked whenever a variable node gains new objects. Implementations
     * may choose to enqueue additional propagation work.
     */
    default void handleVarPoints(VarNode varNode, Set<Obj> newObjects,
                                 NodeEnqueuer enqueuer, Graph graph) {
        // no-op for field-insensitive policy
    }

    /**
     * Functional interface to enqueue new work in the solver without exposing
     * its internal work-list.
     */
    @FunctionalInterface
    interface NodeEnqueuer extends BiConsumer<Node, Set<Obj>> {
        @Override
        void accept(Node node, Set<Obj> objects);
    }
}
