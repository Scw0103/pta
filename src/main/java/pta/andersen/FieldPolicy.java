package pta.andersen;

import java.util.Set;
import java.util.function.BiConsumer;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.proginfo.FieldRef;
import pta.andersen.ModularAndersenSolver.Graph;
import pta.andersen.ModularAndersenSolver.Node;
import pta.andersen.ModularAndersenSolver.PointsRepository;
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
     * 绑定求解器内部的 points-to 仓库，便于域敏感策略在新增边时进行补传播。
     */
    default void bind(PointsRepository repository) {
        // 默认策略无需访问 points-to 数据
    }

    /**
     * 注册一次实例字段写操作。{@code summaryNode} 为求解器准备的字段汇总节点，域敏感策略可选择弃用。
     */
    default void registerStoreField(VarNode base, FieldRef fieldRef, VarNode value,
                                    Node summaryNode, Graph graph, NodeEnqueuer enqueuer) {
        registerBase(base);
        graph.addEdge(value, summaryNode);
    }

    /**
     * 注册一次实例字段读操作。{@code summaryNode} 为求解器准备的字段汇总节点，域敏感策略可选择弃用。
     */
    default void registerLoadField(VarNode base, FieldRef fieldRef, VarNode target,
                                   Node summaryNode, Graph graph, NodeEnqueuer enqueuer) {
        registerBase(base);
        graph.addEdge(summaryNode, target);
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
