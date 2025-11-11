package pta.andersen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.proginfo.FieldRef;
import pta.andersen.ModularAndersenSolver.Graph;
import pta.andersen.ModularAndersenSolver.Node;
import pta.andersen.ModularAndersenSolver.PointsRepository;
import pta.andersen.ModularAndersenSolver.VarNode;

/**
 * 域敏感策略：为每个 (对象, 字段) 维护独立节点来建模字段值，
 * 从而区分不同实例上同名字段的 points-to 集。
 */
public final class FieldSensitivePolicy implements FieldPolicy {

    private final Map<VarNode, BaseInfo> bases = new HashMap<>();
    private PointsRepository pointsRepository;

    @Override
    public void bind(PointsRepository repository) {
        this.pointsRepository = repository;
    }

    @Override
    public void registerStoreField(VarNode base, FieldRef fieldRef, VarNode value,
                                    Node summaryNode, Graph graph, NodeEnqueuer enqueuer) {
        BaseInfo baseInfo = bases.computeIfAbsent(base, key -> new BaseInfo());
        FieldSlot slot = baseInfo.slots.computeIfAbsent(fieldRef, key -> new FieldSlot());
        slot.storeSources.add(value);
        // 若 base 已知对象，则立即为这些对象生成字段节点并连边。
        if (pointsRepository != null) {
            for (Obj obj : pointsRepository.get(base)) {
                ObjectFieldNode objectNode = slot.objectNodes.computeIfAbsent(obj, key -> new ObjectFieldNode(key, fieldRef));
                graph.addEdge(value, objectNode);
                propagateExisting(value, objectNode, enqueuer);
            }
        }
    }

    @Override
    public void registerLoadField(VarNode base, FieldRef fieldRef, VarNode target,
                                   Node summaryNode, Graph graph, NodeEnqueuer enqueuer) {
        BaseInfo baseInfo = bases.computeIfAbsent(base, key -> new BaseInfo());
        FieldSlot slot = baseInfo.slots.computeIfAbsent(fieldRef, key -> new FieldSlot());
        slot.loadTargets.add(target);
        if (pointsRepository != null) {
            for (Obj obj : pointsRepository.get(base)) {
                ObjectFieldNode objectNode = slot.objectNodes.computeIfAbsent(obj, key -> new ObjectFieldNode(key, fieldRef));
                graph.addEdge(objectNode, target);
                propagateExisting(objectNode, target, enqueuer);
            }
        }
    }

    @Override
    public void handleVarPoints(VarNode varNode, Set<Obj> newObjects,
                                 NodeEnqueuer enqueuer, Graph graph) {
        if (newObjects == null || newObjects.isEmpty()) {
            return;
        }
        BaseInfo baseInfo = bases.get(varNode);
        if (baseInfo == null) {
            return;
        }
        for (Obj obj : newObjects) {
            for (Map.Entry<FieldRef, FieldSlot> entry : baseInfo.slots.entrySet()) {
                FieldRef fieldRef = entry.getKey();
                FieldSlot slot = entry.getValue();
                ObjectFieldNode objectNode = slot.objectNodes.computeIfAbsent(obj, key -> new ObjectFieldNode(key, fieldRef));
                for (VarNode storeSource : slot.storeSources) {
                    graph.addEdge(storeSource, objectNode);
                    propagateExisting(storeSource, objectNode, enqueuer);
                }
                for (VarNode loadTarget : slot.loadTargets) {
                    graph.addEdge(objectNode, loadTarget);
                    propagateExisting(objectNode, loadTarget, enqueuer);
                }
            }
        }
    }

    private void propagateExisting(Node from, Node to, NodeEnqueuer enqueuer) {
        if (pointsRepository == null) {
            return;
        }
        Set<Obj> current = pointsRepository.get(from);
        if (current != null && !current.isEmpty()) {
            enqueuer.accept(to, new HashSet<>(current));
        }
    }

    private static final class BaseInfo {
        final Map<FieldRef, FieldSlot> slots = new HashMap<>();
    }

    private static final class FieldSlot {
        final Set<VarNode> storeSources = new HashSet<>();
        final Set<VarNode> loadTargets = new HashSet<>();
        final Map<Obj, ObjectFieldNode> objectNodes = new HashMap<>();
    }

    /**
     * 对象字段节点，每个对象及其字段独占一份，用于高精度传播。
     */
    private static final class ObjectFieldNode implements Node {
        final Obj obj;
        final FieldRef fieldRef;

        ObjectFieldNode(Obj obj, FieldRef fieldRef) {
            this.obj = obj;
            this.fieldRef = fieldRef;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ObjectFieldNode other)) {
                return false;
            }
            return obj.equals(other.obj) && fieldRef.equals(other.fieldRef);
        }

        @Override
        public int hashCode() {
            int result = obj.hashCode();
            result = 31 * result + fieldRef.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "ObjectFieldNode{" + obj + ':' + fieldRef + '}';
        }
    }
}
