package pta.andersen;

import pku.PointerAnalysisResult;
import pku.PreprocessResult;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.classes.JMethod;
import pascal.taie.analysis.misc.IRDumper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A modular, field-insensitive Andersen-style pointer analysis tailored for Taie.
 *
 * <p>The implementation is intentionally structured into small, replaceable components:
 * {@link Graph}, {@link PropagationEngine}, {@link ConstraintCollector}, and {@link CallDispatcher}.
 * Replacing any of these pieces (for example, with a field-sensitive policy) only
 * requires providing alternative strategy implementations without rewriting the solver core.</p>
 */
public final class ModularAndersenSolver {

    private static final Logger logger = LogManager.getLogger(IRDumper.class);

    private final HeapModel heapModel;
    private final FieldPolicy fieldPolicy;

    private final Graph graph = new Graph();
    private final PointsRepository pointsRepository = new PointsRepository();
    private final Deque<WorkItem> workList = new ArrayDeque<>();

    private final Map<JMethod, MethodSummary> methodSummaries = new HashMap<>();
    private final Map<Invoke, CallSiteRecord> callSites = new HashMap<>();
    private final Map<Var, VarNode> varNodes = new HashMap<>();
    private final Map<FieldRef, FieldNode> fieldNodes = new HashMap<>();
    private final Map<FieldRef, FieldNode> staticFieldNodes = new HashMap<>();
    private final Map<Var, ArrayNode> arrayNodes = new HashMap<>();

    private final Map<VarNode, List<CallSiteRecord>> receivers = new HashMap<>();
    private final Set<JMethod> enqueuedMethods = new HashSet<>();

    private final DefaultCallGraph callGraph = new DefaultCallGraph();

    private PreprocessResult preprocessResult;
    private PointerAnalysisResult finalResult;

    ModularAndersenSolver(HeapModel heapModel, FieldPolicy fieldPolicy) {
        this.heapModel = heapModel;
        this.fieldPolicy = fieldPolicy;
    }

    PointerAnalysisResult solve() {
        initialize();
        processWorkList();
        dumpResult();
        return finalResult;
    }

    // ------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------

    private void initialize() {
        preprocessResult = new PreprocessResult();
        finalResult = new PointerAnalysisResult();

        World.get().getClassHierarchy().applicationClasses().forEach(jclass -> {
            logger.info("Indexing class {}", jclass.getName());
            jclass.getDeclaredMethods().forEach(method -> {
                if (!method.isAbstract()) {
                    preprocessResult.analysis(method.getIR());
                }
            });
        });

        JMethod entry = World.get().getMainMethod();
        callGraph.addEntryMethod(entry);
        enqueueMethod(entry);
    }

    private void enqueueMethod(JMethod method) {
        if (!enqueuedMethods.add(method)) {
            return;
        }
        MethodSummary summary = methodSummaries.computeIfAbsent(method, this::createSummary);
        logger.debug("Processing method {}", method.getSignature());
        method.getIR().getStmts().forEach(stmt -> stmt.accept(new ConstraintCollector(summary)));
    }

    private MethodSummary createSummary(JMethod method) {
        MethodSummary summary = new MethodSummary(method);
        if (!method.isStatic()) {
            summary.thisNode = getVarNode(method.getIR().getThis());
        }
        method.getIR().getParams().forEach(var -> summary.formals.add(getVarNode(var)));
        method.getIR().getReturnVars().forEach(var -> summary.returns.add(getVarNode(var)));
        return summary;
    }

    // ------------------------------------------------------------
    // Constraint collection
    // ------------------------------------------------------------

    private final class ConstraintCollector implements StmtVisitor<Void> {

    @SuppressWarnings("unused")
    private final MethodSummary summary;

        private ConstraintCollector(MethodSummary summary) {
            this.summary = summary;
        }

        @Override
        public Void visit(New stmt) {
            VarNode target = getVarNode(stmt.getLValue());
            Obj obj = heapModel.getObj(stmt);
            enqueue(target, Set.of(obj));
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            VarNode from = getVarNode(stmt.getRValue());
            VarNode to = getVarNode(stmt.getLValue());
            graph.addEdge(from, to);
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            VarNode value = getVarNode(stmt.getRValue());
            if (stmt.isStatic()) {
                FieldAccess access = stmt.getFieldAccess();
                FieldNode field = getStaticFieldNode(access.getFieldRef());
                graph.addEdge(value, field);
            } else {
                InstanceFieldAccess instanceAccess =
                        (InstanceFieldAccess) stmt.getFieldAccess();
                FieldNode field = getInstanceFieldNode(instanceAccess.getFieldRef());
                graph.addEdge(value, field);
                VarNode base = getVarNode(instanceAccess.getBase());
                fieldPolicy.registerBase(base);
            }
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            VarNode target = getVarNode(stmt.getLValue());
            if (stmt.isStatic()) {
                FieldAccess access = stmt.getFieldAccess();
                FieldNode field = getStaticFieldNode(access.getFieldRef());
                graph.addEdge(field, target);
            } else {
                InstanceFieldAccess instanceAccess =
                        (InstanceFieldAccess) stmt.getFieldAccess();
                FieldNode field = getInstanceFieldNode(instanceAccess.getFieldRef());
                graph.addEdge(field, target);
                VarNode base = getVarNode(instanceAccess.getBase());
                fieldPolicy.registerBase(base);
            }
            return null;
        }

        @Override
        public Void visit(StoreArray stmt) {
            VarNode value = getVarNode(stmt.getRValue());
            ArrayAccess access = stmt.getArrayAccess();
            ArrayNode array = getArrayNode(access.getBase());
            graph.addEdge(value, array);
            return null;
        }

        @Override
        public Void visit(LoadArray stmt) {
            VarNode target = getVarNode(stmt.getLValue());
            ArrayAccess access = stmt.getArrayAccess();
            ArrayNode array = getArrayNode(access.getBase());
            graph.addEdge(array, target);
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            CallSiteRecord site = buildCallSite(stmt);
            callSites.put(stmt, site);
            if (stmt.isStatic()) {
                JMethod callee = resolveStatic(stmt);
                dispatchCall(site, callee, null);
            } else {
                receivers.computeIfAbsent(site.receiver, key -> new ArrayList<>()).add(site);
            }
            return null;
        }
    }

    private CallSiteRecord buildCallSite(Invoke stmt) {
        VarNode receiver = null;
        if (!stmt.isStatic()) {
            InvokeInstanceExp instanceExp = (InvokeInstanceExp) stmt.getInvokeExp();
            receiver = getVarNode(instanceExp.getBase());
        }
        List<VarNode> args = new ArrayList<>();
        InvokeExp invokeExp = stmt.getInvokeExp();
        for (int i = 0; i < invokeExp.getArgCount(); i++) {
            args.add(getVarNode(invokeExp.getArg(i)));
        }
        VarNode result = stmt.getLValue() != null ? getVarNode(stmt.getLValue()) : null;
        return new CallSiteRecord(stmt, receiver, result, args);
    }

    private JMethod resolveStatic(Invoke stmt) {
        MethodRef ref = stmt.getMethodRef();
        return ref.resolve();
    }

    private void dispatchCall(CallSiteRecord site, JMethod callee, Obj receiverObj) {
        if (callee == null) {
            return;
        }
        if (!site.resolvedCallees.add(callee)) {
            return; // already wired
        }
        enqueueMethod(callee);
        MethodSummary summary = methodSummaries.get(callee);

        if (callee.isStatic()) {
            for (int i = 0; i < site.arguments.size() && i < summary.formals.size(); i++) {
                graph.addEdge(site.arguments.get(i), summary.formals.get(i));
            }
        } else {
            if (summary.thisNode != null && receiverObj != null) {
                enqueue(summary.thisNode, Set.of(receiverObj));
            }
            int paramCount = Math.min(site.arguments.size(), summary.formals.size());
            for (int i = 0; i < paramCount; i++) {
                graph.addEdge(site.arguments.get(i), summary.formals.get(i));
            }
        }
        if (site.result != null) {
            summary.returns.forEach(ret -> graph.addEdge(ret, site.result));
        }

        CallKind kind = site.invoke.isStatic() ? CallKind.STATIC : (site.invoke.isInterface() ? CallKind.INTERFACE
            : site.invoke.isSpecial() ? CallKind.SPECIAL : CallKind.VIRTUAL);
        if (kind != null) {
            callGraph.addEdge(new Edge<>(kind, site.invoke, callee));
        }
    }

    // ------------------------------------------------------------
    // Propagation
    // ------------------------------------------------------------

    private void processWorkList() {
        while (!workList.isEmpty()) {
            WorkItem item = workList.pollFirst();
            Node node = item.node;
            Set<Obj> diff = pointsRepository.add(node, item.objects);
            if (diff.isEmpty()) {
                continue;
            }
            for (Node succ : graph.getSuccessors(node)) {
                enqueue(succ, diff);
            }
            if (node instanceof VarNode varNode) {
                fieldPolicy.handleVarPoints(varNode, diff, this::enqueue, graph);
                propagateCalls(varNode, diff);
            }
        }
    }

    private void propagateCalls(VarNode varNode, Set<Obj> newObjects) {
        List<CallSiteRecord> callSitesForVar = receivers.get(varNode);
        if (callSitesForVar == null) {
            return;
        }
        for (CallSiteRecord site : callSitesForVar) {
            for (Obj obj : newObjects) {
                JMethod callee = CallGraphs.resolveCallee(obj.getType(), site.invoke);
                dispatchCall(site, callee, obj);
            }
        }
    }

    private void enqueue(Node node, Set<Obj> objects) {
        if (objects == null || objects.isEmpty()) {
            return;
        }
        workList.addLast(new WorkItem(node, objects));
    }

    // ------------------------------------------------------------
    // Result materialisation
    // ------------------------------------------------------------

    private void dumpResult() {
        preprocessResult.test_pts.forEach((testId, var) -> {
            VarNode node = getVarNode(var);
            Set<Obj> pts = pointsRepository.get(node);
            TreeSet<Integer> indices = new TreeSet<>();
            if (pts != null) {
                for (Obj obj : pts) {
                    Object allocation = obj.getAllocation();
                    if (allocation instanceof New newStmt) {
                        int id = preprocessResult.getObjIdAt(newStmt);
                        if (id > 0) {
                            indices.add(id);
                        }
                    }
                }
            }
            finalResult.put(testId, indices);
        });
        dumpToFile(finalResult);
    }

    private void dumpToFile(PointerAnalysisResult result) {
        File path = new File("result.txt");
        try (PrintStream out = new PrintStream(new FileOutputStream(path))) {
            out.println(result);
        } catch (FileNotFoundException e) {
            logger.warn("Unable to dump pointer analysis result", e);
        }
    }

    // ------------------------------------------------------------
    // Node factories
    // ------------------------------------------------------------

    private VarNode getVarNode(Var var) {
        return varNodes.computeIfAbsent(var, VarNode::new);
    }

    private FieldNode getInstanceFieldNode(FieldRef ref) {
        return fieldNodes.computeIfAbsent(ref, key -> new FieldNode(key, false));
    }

    private FieldNode getStaticFieldNode(FieldRef ref) {
        return staticFieldNodes.computeIfAbsent(ref, key -> new FieldNode(key, true));
    }

    private ArrayNode getArrayNode(Var arrayVar) {
        return arrayNodes.computeIfAbsent(arrayVar, ArrayNode::new);
    }

    // ------------------------------------------------------------
    // Helper data structures
    // ------------------------------------------------------------

    private record WorkItem(Node node, Set<Obj> objects) { }

    static final class PointsRepository {
        private final Map<Node, Set<Obj>> pointsTo = new HashMap<>();

        Set<Obj> add(Node node, Collection<Obj> objects) {
            if (objects == null || objects.isEmpty()) {
                return Set.of();
            }
            Set<Obj> current = pointsTo.computeIfAbsent(node, key -> new HashSet<>());
            Set<Obj> diff = new HashSet<>();
            for (Obj obj : objects) {
                if (current.add(obj)) {
                    diff.add(obj);
                }
            }
            return diff;
        }

        Set<Obj> get(Node node) {
            return pointsTo.getOrDefault(node, Set.of());
        }
    }

    static final class Graph {
        private final Map<Node, Set<Node>> edges = new HashMap<>();

        void addEdge(Node from, Node to) {
            if (from == null || to == null) {
                return;
            }
            edges.computeIfAbsent(from, key -> new HashSet<>()).add(to);
        }

        Set<Node> getSuccessors(Node node) {
            return edges.getOrDefault(node, Set.of());
        }
    }

    interface Node { }

    static final class VarNode implements Node {
        final Var var;

        VarNode(Var var) {
            this.var = Objects.requireNonNull(var);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof VarNode other && var.equals(other.var);
        }

        @Override
        public int hashCode() {
            return var.hashCode();
        }

        @Override
        public String toString() {
            return "VarNode{" + var + '}';
        }
    }

    static final class FieldNode implements Node {
        final FieldRef fieldRef;
        final boolean isStatic;

        FieldNode(FieldRef ref, boolean isStatic) {
            this.fieldRef = Objects.requireNonNull(ref);
            this.isStatic = isStatic;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof FieldNode other)) {
                return false;
            }
            return isStatic == other.isStatic && fieldRef.equals(other.fieldRef);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fieldRef, isStatic);
        }

        @Override
        public String toString() {
            return (isStatic ? "StaticField" : "Field") + '{' + fieldRef + '}';
        }
    }

    static final class ArrayNode implements Node {
        final Var arrayVar;

        ArrayNode(Var arrayVar) {
            this.arrayVar = Objects.requireNonNull(arrayVar);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ArrayNode other && arrayVar.equals(other.arrayVar);
        }

        @Override
        public int hashCode() {
            return arrayVar.hashCode();
        }

        @Override
        public String toString() {
            return "ArrayNode{" + arrayVar + '}';
        }
    }

    private static final class MethodSummary {
    @SuppressWarnings("unused")
    final JMethod method;
        final List<VarNode> formals = new ArrayList<>();
        final List<VarNode> returns = new ArrayList<>();
        VarNode thisNode;

        MethodSummary(JMethod method) {
            this.method = method;
        }
    }

    private static final class CallSiteRecord {
        final Invoke invoke;
        final VarNode receiver;
        final VarNode result;
        final List<VarNode> arguments;
        final Set<JMethod> resolvedCallees = new HashSet<>();

        CallSiteRecord(Invoke invoke, VarNode receiver, VarNode result, List<VarNode> arguments) {
            this.invoke = invoke;
            this.receiver = receiver;
            this.result = result;
            this.arguments = arguments;
        }
    }
}
