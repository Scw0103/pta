package pta.andersen;

import pku.PointerAnalysisResult;
import pku.PreprocessResult;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.InvokeDynamic;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Literal;
import pascal.taie.ir.exp.ReferenceLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Catch;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.Throw;
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
import java.util.concurrent.TimeUnit;

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
    private final int contextDepth;
    private final int objectDepth;
    private final long timeBudgetNanos;

    private static final Descriptor INVOKEDYNAMIC_DESC = () -> "InvokeDynamicObj";

    private long startTime;
    private boolean aborted;

    // 约束传播使用的有向图：节点为变量/字段/数组，边表示 points-to 流向
    private final Graph graph = new Graph();
    private final PointsRepository pointsRepository = new PointsRepository();
    private final Deque<WorkItem> workList = new ArrayDeque<>();

    private final Map<MethodKey, MethodSummary> methodSummaries = new HashMap<>();
    private final Map<CallSiteKey, CallSiteRecord> callSites = new HashMap<>();
    private final Map<VarKey, VarNode> varNodes = new HashMap<>();
    private final Map<FieldKey, FieldNode> fieldNodes = new HashMap<>();
    private final Map<FieldRef, FieldNode> staticFieldNodes = new HashMap<>();
    private final Map<ArrayKey, ArrayNode> arrayNodes = new HashMap<>();

    private final Map<VarNode, List<CallSiteRecord>> receivers = new HashMap<>();
    private final Set<MethodKey> enqueuedMethods = new HashSet<>();

    private final DefaultCallGraph callGraph = new DefaultCallGraph();

    private PreprocessResult preprocessResult;
    private PointerAnalysisResult finalResult;

    ModularAndersenSolver(HeapModel heapModel, FieldPolicy fieldPolicy, int contextDepth, int objectDepth) {
        this.heapModel = Objects.requireNonNull(heapModel);
        this.fieldPolicy = Objects.requireNonNull(fieldPolicy);
        this.contextDepth = Math.max(0, contextDepth);
        this.objectDepth = Math.max(0, objectDepth);
    this.timeBudgetNanos = TimeUnit.SECONDS.toNanos(59);
        this.fieldPolicy.bind(pointsRepository);
    }

    PointerAnalysisResult solve() {
        startTime = System.nanoTime();
        aborted = false;
        initialize();
        if (!aborted) {
            processWorkList();
        }
        if (aborted) {
            return finalResult;
        }
        dumpResult();
        return finalResult;
    }

    // ------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------

    private void initialize() {
        // 每次求解前都重置预处理信息和最终结果容器
        preprocessResult = new PreprocessResult();
        finalResult = new PointerAnalysisResult();

        World.get().getClassHierarchy().applicationClasses().forEach(jclass -> {
            logger.info("Indexing class {}", jclass.getName());
            jclass.getDeclaredMethods().forEach(method -> {
                if (!method.isAbstract()) {
                    // 预处理阶段解析 Benchmark.alloc/test 标签，记录对象编号与测试点
                    preprocessResult.analysis(method.getIR());
                }
            });
        });

    JMethod entry = World.get().getMainMethod();
    callGraph.addEntryMethod(entry);
    enqueueMethod(entry, Context.root());
    }

    private void enqueueMethod(JMethod method, Context context) {
        MethodKey key = new MethodKey(method, context);
        if (!enqueuedMethods.add(key)) {
            return;
        }
        MethodSummary summary = methodSummaries.computeIfAbsent(key,
                k -> createSummary(k.method(), k.context()));
        logger.debug("Processing method {} @ {}", method.getSignature(), context);
        // 首次遇到该方法时，对 IR 中的每条语句收集约束
        method.getIR().getStmts().forEach(stmt -> stmt.accept(new ConstraintCollector(summary, context)));
    }

    private MethodSummary createSummary(JMethod method, Context context) {
        MethodSummary summary = new MethodSummary(method, context);
        if (!method.isStatic()) {
            summary.thisNode = getVarNode(method.getIR().getThis(), context);
        }
        method.getIR().getParams().forEach(var -> summary.formals.add(getVarNode(var, context)));
        method.getIR().getReturnVars().forEach(var -> summary.returns.add(getVarNode(var, context)));
        return summary;
    }

    // ------------------------------------------------------------
    // Constraint collection
    // ------------------------------------------------------------

    /**
     * ConstraintCollector 将 IR 语句转换为图上的边或待传播对象：
     * <ul>
     *   <li>标量赋值/字段/数组操作：统一建边到 {@link Graph}，在传播阶段沿边扩散 points-to。</li>
     *   <li>对象创建：直接将新抽象对象加入工作队列，以便立即触发传播。</li>
     *   <li>调用语句：构造 {@link CallSiteRecord}，静态调用即时接线，实例调用延迟到接收者集更新。</li>
     * </ul>
     * 收集过程中所有 Var/Field/Array 都经由 Node factory 缓存，确保同一 IR 元素映射到唯一节点。
     */
    private final class ConstraintCollector implements StmtVisitor<Void> {

    private final MethodSummary summary;
        private final Context context;

        private ConstraintCollector(MethodSummary summary, Context context) {
            this.summary = summary;
            this.context = context;
        }

        @Override
        public Void visit(New stmt) {
            VarNode target = getVarNode(stmt.getLValue(), context);
            Obj obj = heapModel.getObj(stmt);
            // new 语句：立即将抽象对象放入工作队列，触发后续传播
            enqueue(target, Set.of(obj));
            return null;
        }

        @Override
        public Void visit(Cast stmt) {
            VarNode from = getVarNode(stmt.getRValue().getValue(), context);
            VarNode to = getVarNode(stmt.getLValue(), context);
            graph.addEdge(from, to);
            return null;
        }

        @Override
        public Void visit(AssignLiteral stmt) {
            Literal literal = stmt.getRValue();
            if (literal instanceof ReferenceLiteral referenceLiteral) {
                Obj constantObj = heapModel.getConstantObj(referenceLiteral);
                if (constantObj != null) {
                    VarNode target = getVarNode(stmt.getLValue(), context);
                    enqueue(target, Set.of(constantObj));
                }
            }
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            VarNode from = getVarNode(stmt.getRValue(), context);
            VarNode to = getVarNode(stmt.getLValue(), context);
            // 标量赋值：记录一条 from -> to 的流向边
            graph.addEdge(from, to);
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            VarNode value = getVarNode(stmt.getRValue(), context);
            if (stmt.isStatic()) {
                FieldAccess access = stmt.getFieldAccess();
                FieldNode field = getStaticFieldNode(access.getFieldRef());
                // 静态字段写：所有对象共享，直接 value -> staticField
                graph.addEdge(value, field);
            } else {
                InstanceFieldAccess instanceAccess =
                        (InstanceFieldAccess) stmt.getFieldAccess();
                FieldRef fieldRef = instanceAccess.getFieldRef();
                FieldNode summary = getInstanceFieldNode(fieldRef, context);
                VarNode base = getVarNode(instanceAccess.getBase(), context);
                // 实例字段写：交由 FieldPolicy 选择拆分策略
                fieldPolicy.registerStoreField(base, fieldRef, value, summary, graph, ModularAndersenSolver.this::enqueue);
            }
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            VarNode target = getVarNode(stmt.getLValue(), context);
            if (stmt.isStatic()) {
                FieldAccess access = stmt.getFieldAccess();
                FieldNode field = getStaticFieldNode(access.getFieldRef());
                // 静态字段读：staticField -> target
                graph.addEdge(field, target);
            } else {
                InstanceFieldAccess instanceAccess =
                        (InstanceFieldAccess) stmt.getFieldAccess();
                FieldRef fieldRef = instanceAccess.getFieldRef();
                FieldNode summary = getInstanceFieldNode(fieldRef, context);
                VarNode base = getVarNode(instanceAccess.getBase(), context);
                // 实例字段读：完全交给 FieldPolicy 控制传播行为
                fieldPolicy.registerLoadField(base, fieldRef, target, summary, graph, ModularAndersenSolver.this::enqueue);
            }
            return null;
        }

        @Override
        public Void visit(StoreArray stmt) {
            VarNode value = getVarNode(stmt.getRValue(), context);
            ArrayAccess access = stmt.getArrayAccess();
            ArrayNode array = getArrayNode(access.getBase(), context);
            // 数组写：按 field-insensitive 策略把整个数组视为单节点
            graph.addEdge(value, array);
            return null;
        }

        @Override
        public Void visit(LoadArray stmt) {
            VarNode target = getVarNode(stmt.getLValue(), context);
            ArrayAccess access = stmt.getArrayAccess();
            ArrayNode array = getArrayNode(access.getBase(), context);
            // 数组读：array -> target
            graph.addEdge(array, target);
            return null;
        }

        @Override
        public Void visit(Catch stmt) {
            VarNode catcher = getVarNode(stmt.getExceptionRef(), context);
            logger.debug("Registering catch {} with {} pending throwers", catcher, summary.throwers.size());
            summary.catches.add(catcher);
            summary.throwers.forEach(thrower -> {
                graph.addEdge(thrower, catcher);
                Set<Obj> existing = pointsRepository.get(thrower);
                if (!existing.isEmpty()) {
                    enqueue(catcher, existing);
                }
            });
            return null;
        }

        @Override
        public Void visit(Throw stmt) {
            VarNode thrown = getVarNode(stmt.getExceptionRef(), context);
            logger.debug("Linking throw {} to {} catches", thrown, summary.catches.size());
            summary.catches.forEach(catcher -> {
                graph.addEdge(thrown, catcher);
                Set<Obj> existing = pointsRepository.get(thrown);
                if (!existing.isEmpty()) {
                    enqueue(catcher, existing);
                }
            });
            summary.throwers.add(thrown);
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            if (stmt.isDynamic()) {
                handleInvokeDynamic(stmt);
                return null;
            }
            CallSiteRecord site = buildCallSite(stmt, context);
            callSites.put(new CallSiteKey(stmt, context), site);
            if (stmt.isStatic()) {
                JMethod callee = resolveStatic(stmt);
                // 静态调用：类型已知，立即完成参数/返回连线
                dispatchCall(site, callee, null);
            } else {
                // 实例调用：延迟到接收者 points-to 更新时再解析虚调用
                receivers.computeIfAbsent(site.receiver, key -> new ArrayList<>()).add(site);
            }
            return null;
        }

        private void handleInvokeDynamic(Invoke stmt) {
            if (stmt.getLValue() == null) {
                return;
            }
            InvokeDynamic dynamicExp = (InvokeDynamic) stmt.getInvokeExp();
            if (dynamicExp.getType() == null) {
                return;
            }
            VarNode target = getVarNode(stmt.getLValue(), context);
            Obj mock = heapModel.getMockObj(INVOKEDYNAMIC_DESC, stmt,
                    dynamicExp.getType(), stmt.getContainer());
            enqueue(target, Set.of(mock));
        }
    }

    private CallSiteRecord buildCallSite(Invoke stmt, Context context) {
        VarNode receiver = null;
        if (!stmt.isStatic()) {
            InvokeInstanceExp instanceExp = (InvokeInstanceExp) stmt.getInvokeExp();
            receiver = getVarNode(instanceExp.getBase(), context);
        }
        List<VarNode> args = new ArrayList<>();
        InvokeExp invokeExp = stmt.getInvokeExp();
        for (int i = 0; i < invokeExp.getArgCount(); i++) {
            args.add(getVarNode(invokeExp.getArg(i), context));
        }
        VarNode result = stmt.getLValue() != null ? getVarNode(stmt.getLValue(), context) : null;
        return new CallSiteRecord(stmt, context, receiver, result, args);
    }

    private JMethod resolveStatic(Invoke stmt) {
        MethodRef ref = stmt.getMethodRef();
        return ref.resolve();
    }

    private void dispatchCall(CallSiteRecord site, JMethod callee, Obj receiverObj) {
        if (callee == null) {
            return;
        }
    Context calleeContext = deriveContext(site.context, site.invoke, receiverObj);
        MethodKey calleeKey = new MethodKey(callee, calleeContext);
        if (!site.resolvedCallees.add(calleeKey)) {
            return;
        }
        enqueueMethod(callee, calleeContext);
        MethodSummary summary = methodSummaries.get(calleeKey);

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

    private Context deriveContext(Context callerContext, Invoke invoke, Obj receiverObj) {
        if (contextDepth <= 0 && objectDepth <= 0) {
            return Context.root();
        }
        return callerContext.push(invoke, receiverObj, contextDepth, objectDepth);
    }

    // ------------------------------------------------------------
    // Propagation
    // ------------------------------------------------------------

    private void processWorkList() {
        while (!workList.isEmpty()) {
            if (timeExceeded()) {
                abortAndBuildTrivial();
                return;
            }
            WorkItem item = workList.pollFirst();
            Node node = item.node;
            Set<Obj> diff = pointsRepository.add(node, item.objects);
            if (diff.isEmpty()) {
                continue;
            }
            // 将新增的对象沿图上的边继续传播
            for (Node succ : graph.getSuccessors(node)) {
                enqueue(succ, diff);
            }
            if (node instanceof VarNode varNode) {
                // 域策略可以基于新对象展开附加约束（如字段敏感）
                fieldPolicy.handleVarPoints(varNode, diff, this::enqueue, graph);
                propagateCalls(varNode, diff);
                if (aborted) {
                    return;
                }
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
                if (timeExceeded()) {
                    abortAndBuildTrivial();
                    return;
                }
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
            Set<Obj> allPts = new HashSet<>();
            // Aggregate points-to sets from all contexts for this variable
            for (VarNode node : varNodes.values()) {
                if (node.var.equals(var)) {
                    Set<Obj> pts = pointsRepository.get(node);
                    if (pts != null) {
                        allPts.addAll(pts);
                    }
                }
            }
            logger.debug("Test id {} on var {} has objects {}", testId, var.getName(), allPts);
            TreeSet<Integer> indices = new TreeSet<>();
            for (Obj obj : allPts) {
                Object allocation = obj.getAllocation();
                if (allocation instanceof New newStmt) {
                    int id = preprocessResult.getObjIdAt(newStmt);
                    if (id > 0) {
                        indices.add(id);
                    }
                }
            }
            // 最终结果采用测试点编号映射到对象编号集合
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

    private boolean timeExceeded() {
        return !aborted && timeBudgetNanos > 0 && System.nanoTime() - startTime >= timeBudgetNanos;
    }

    private void abortAndBuildTrivial() {
        if (aborted) {
            return;
        }
        aborted = true;
        logger.warn("Time budget exceeded ({}s), falling back to trivial result", TimeUnit.NANOSECONDS.toSeconds(timeBudgetNanos));
        buildTrivialResult();
    }

    private void buildTrivialResult() {
        PointerAnalysisResult result = new PointerAnalysisResult();
        Set<Integer> universe = buildUniverse();
        if (preprocessResult != null) {
            preprocessResult.test_pts.keySet().forEach(id -> {
                TreeSet<Integer> pts = new TreeSet<>(universe);
                result.put(id, pts);
            });
        }
        finalResult = result;
        dumpToFile(result);
    }

    private Set<Integer> buildUniverse() {
        Set<Integer> universe = new TreeSet<>();
        if (preprocessResult == null) {
            return universe;
        }
        preprocessResult.obj_ids.values().forEach(universe::add);
        return universe;
    }

    // ------------------------------------------------------------
    // Node factories
    // ------------------------------------------------------------

    private VarNode getVarNode(Var var, Context context) {
        VarKey key = new VarKey(context, var);
        return varNodes.computeIfAbsent(key, k -> new VarNode(k.context(), k.var()));
    }

    private FieldNode getInstanceFieldNode(FieldRef ref, Context context) {
        FieldKey key = new FieldKey(context, ref);
        return fieldNodes.computeIfAbsent(key, k -> new FieldNode(k.fieldRef(), false, k.context()));
    }

    private FieldNode getStaticFieldNode(FieldRef ref) {
        return staticFieldNodes.computeIfAbsent(ref, key -> new FieldNode(key, true, Context.root()));
    }

    private ArrayNode getArrayNode(Var arrayVar, Context context) {
        ArrayKey key = new ArrayKey(context, arrayVar);
        return arrayNodes.computeIfAbsent(key, k -> new ArrayNode(k.context(), k.arrayVar()));
    }

    // ------------------------------------------------------------
    // Helper data structures
    // ------------------------------------------------------------

    private record WorkItem(Node node, Set<Obj> objects) { }

    private record MethodKey(JMethod method, Context context) { }

    private record CallSiteKey(Invoke invoke, Context context) { }

    private record VarKey(Context context, Var var) { }

    private record FieldKey(Context context, FieldRef fieldRef) { }

    private record ArrayKey(Context context, Var arrayVar) { }

    /**
     * Immutable context that keeps both call-string and object-sensitive information.
     */
    private static final class Context {
        private static final Context ROOT = new Context(List.of(), List.of());

        private final List<Invoke> callFrames;   // most recent call first
        private final List<Obj> objectFrames;    // most recent receiver first

        private Context(List<Invoke> callFrames, List<Obj> objectFrames) {
            this.callFrames = callFrames;
            this.objectFrames = objectFrames;
        }

        static Context root() {
            return ROOT;
        }

        Context push(Invoke invoke, Obj receiver, int maxCallDepth, int maxObjectDepth) {
            List<Invoke> nextCalls;
            if (maxCallDepth <= 0) {
                nextCalls = List.of();
            } else {
                List<Invoke> tmp = new ArrayList<>(Math.min(maxCallDepth, callFrames.size() + 1));
                tmp.add(invoke);
                for (int i = 0; i < callFrames.size() && i < maxCallDepth - 1; i++) {
                    tmp.add(callFrames.get(i));
                }
                nextCalls = List.copyOf(tmp);
            }

            List<Obj> nextObjects;
            if (maxObjectDepth <= 0) {
                nextObjects = List.of();
            } else {
                int remaining = maxObjectDepth;
                List<Obj> tmp = new ArrayList<>(Math.min(maxObjectDepth,
                        objectFrames.size() + (receiver != null ? 1 : 0)));
                if (receiver != null && remaining > 0) {
                    tmp.add(receiver);
                    remaining--;
                }
                for (int i = 0; i < objectFrames.size() && i < remaining; i++) {
                    tmp.add(objectFrames.get(i));
                }
                nextObjects = List.copyOf(tmp);
            }

            if (nextCalls.isEmpty() && nextObjects.isEmpty()) {
                return ROOT;
            }
            if (nextCalls.equals(callFrames) && nextObjects.equals(objectFrames)) {
                return this;
            }
            return new Context(nextCalls, nextObjects);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Context other
                    && callFrames.equals(other.callFrames)
                    && objectFrames.equals(other.objectFrames);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callFrames, objectFrames);
        }

        @Override
        public String toString() {
            if (callFrames.isEmpty() && objectFrames.isEmpty()) {
                return "<root>";
            }
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            for (int i = 0; i < callFrames.size(); i++) {
                if (i > 0) {
                    builder.append(" -> ");
                }
                Invoke invoke = callFrames.get(i);
                builder.append(invoke.getContainer().getName()).append(":").append(invoke.getIndex());
            }
            builder.append(']');
            if (!objectFrames.isEmpty()) {
                builder.append(" @ {");
                for (int i = 0; i < objectFrames.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(objectFrames.get(i));
                }
                builder.append('}');
            }
            return builder.toString();
        }
    }

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
        final Context context;
        final Var var;

        VarNode(Context context, Var var) {
            this.context = Objects.requireNonNull(context);
            this.var = Objects.requireNonNull(var);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof VarNode other)) {
                return false;
            }
            return context.equals(other.context) && var.equals(other.var);
        }

        @Override
        public int hashCode() {
            return Objects.hash(context, var);
        }

        @Override
        public String toString() {
            return "VarNode{" + var + "@" + context + '}';
        }
    }

    static final class FieldNode implements Node {
        final FieldRef fieldRef;
        final boolean isStatic;
        final Context context;

        FieldNode(FieldRef ref, boolean isStatic, Context context) {
            this.fieldRef = Objects.requireNonNull(ref);
            this.isStatic = isStatic;
            this.context = Objects.requireNonNull(context);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof FieldNode other)) {
                return false;
            }
            return isStatic == other.isStatic && fieldRef.equals(other.fieldRef)
                    && context.equals(other.context);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fieldRef, isStatic, context);
        }

        @Override
        public String toString() {
            return (isStatic ? "StaticField" : "Field") + '{' + fieldRef + "@" + context + '}';
        }
    }

    static final class ArrayNode implements Node {
        final Context context;
        final Var arrayVar;

        ArrayNode(Context context, Var arrayVar) {
            this.context = Objects.requireNonNull(context);
            this.arrayVar = Objects.requireNonNull(arrayVar);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ArrayNode other)) {
                return false;
            }
            return context.equals(other.context) && arrayVar.equals(other.arrayVar);
        }

        @Override
        public int hashCode() {
            return Objects.hash(context, arrayVar);
        }

        @Override
        public String toString() {
            return "ArrayNode{" + arrayVar + "@" + context + '}';
        }
    }

    private static final class MethodSummary {
    @SuppressWarnings("unused")
    final JMethod method;
    @SuppressWarnings("unused")
    final Context context;
        final List<VarNode> formals = new ArrayList<>();
        final List<VarNode> returns = new ArrayList<>();
    final List<VarNode> catches = new ArrayList<>();
    final List<VarNode> throwers = new ArrayList<>();
        VarNode thisNode;

        MethodSummary(JMethod method, Context context) {
            this.method = method;
            this.context = context;
        }
    }

    private static final class CallSiteRecord {
        final Invoke invoke;
        final Context context;
        final VarNode receiver;
        final VarNode result;
        final List<VarNode> arguments;
    final Set<MethodKey> resolvedCallees = new HashSet<>();

        CallSiteRecord(Invoke invoke, Context context, VarNode receiver, VarNode result, List<VarNode> arguments) {
            this.invoke = invoke;
            this.context = context;
            this.receiver = receiver;
            this.result = result;
            this.arguments = arguments;
        }
    }

}
