package pta.andersen;

import java.util.Set;

import pascal.taie.analysis.pta.core.heap.Obj;
import pta.andersen.ModularAndersenSolver.Graph;
import pta.andersen.ModularAndersenSolver.VarNode;

/**
 * Field-insensitive policy: treats all instance fields of an object as a single
 * abstraction and arrays monolithically (handled by solver core). The hooks are
 * intentionally left empty.
 */
public final class FieldInsensitivePolicy implements FieldPolicy {

    @Override
    public void registerBase(VarNode base) {
        // nothing to record; all fields share the same abstraction
    }

    @Override
    public void handleVarPoints(VarNode varNode, Set<Obj> newObjects,
                                 NodeEnqueuer enqueuer, Graph graph) {
        // field-insensitive strategy does not derive additional constraints
    }
}
