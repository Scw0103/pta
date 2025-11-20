package pta.andersen;

import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pku.PointerAnalysisResult;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.World;
import pascal.taie.analysis.deadcode.DeadCodeDetection;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.LiveVariable;
import pascal.taie.ir.IR;

/**
 * Entry point that wires the modular Andersen solver into Taie.
 * The analysis itself delegates to {@link ModularAndersenSolver}.
 */
public class AndersenPointerAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "pku-pta";

    private static final Logger logger = LogManager.getLogger(AndersenPointerAnalysis.class);

    public AndersenPointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        HeapModel heapModel = new AllocationSiteBasedModel(getOptions());
        FieldPolicy fieldPolicy = createFieldPolicy();
        int contextDepth = resolveContextDepth();
        if (contextDepth > 0) {
            logger.info("Using {}-clone call-string sensitivity", contextDepth);
        }
        int objectDepth = resolveObjectDepth();
        if (objectDepth > 0) {
            logger.info("Using {}-object-sensitive contexts", objectDepth);
        }
        if (contextDepth == 0 && objectDepth == 0) {
            logger.info("Running in context-insensitive mode");
        }

            // Run required pre-analyses so DeadCodeDetection can produce results
            pascal.taie.analysis.exception.ThrowAnalysis throwAnalysis =
                    new pascal.taie.analysis.exception.ThrowAnalysis(
                            AnalysisConfig.of(pascal.taie.analysis.exception.ThrowAnalysis.ID,
                                    "exception", "explicit", "algorithm", "intra"));
            CFGBuilder cfgBuilder = new CFGBuilder(AnalysisConfig.of(CFGBuilder.ID,
                "exception", "explicit", "dump", false));
            ConstantPropagation constProp = new ConstantPropagation(AnalysisConfig.of(ConstantPropagation.ID,
                "edge-refine", true));
            LiveVariable liveVar = new LiveVariable(AnalysisConfig.of(LiveVariable.ID,
                "strongly", true));
            DeadCodeDetection dead = new DeadCodeDetection(AnalysisConfig.of(DeadCodeDetection.ID));

            World.get().getClassHierarchy().applicationClasses().forEach(jclass -> {
                jclass.getDeclaredMethods().forEach(method -> {
                        if (!method.isAbstract()) {
                            IR ir = method.getIR();
                            try {
                                var throwRes = throwAnalysis.analyze(ir);
                                ir.storeResult(pascal.taie.analysis.exception.ThrowAnalysis.ID, throwRes);
                                var cfg = cfgBuilder.analyze(ir);
                                ir.storeResult(CFGBuilder.ID, cfg);
                                var cp = constProp.analyze(ir);
                                ir.storeResult(ConstantPropagation.ID, cp);
                                var lv = liveVar.analyze(ir);
                                ir.storeResult(LiveVariable.ID, lv);
                                var deadRes = dead.analyze(ir);
                                ir.storeResult(DeadCodeDetection.ID, deadRes);
                            } catch (RuntimeException ex) {
                                logger.warn("Pre-analysis failed for method {}: {}", method.getSignature(), ex.toString());
                            }
                        }
                });
            });

            ModularAndersenSolver solver = new ModularAndersenSolver(heapModel, fieldPolicy, contextDepth, objectDepth);
            return solver.solve();
    }

    private FieldPolicy createFieldPolicy() {
    String option = getOptions().has("field-policy")
        ? getOptions().getString("field-policy")
        : null;
        String normalized = option == null ? "sensitive" : option.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "sensitive":
                logger.info("Using field-sensitive policy for Andersen solver");
                return new FieldSensitivePolicy();
            case "insensitive":
            case "field-insensitive":
            case "fs":
            case "fi":
                logger.info("Using field-insensitive policy for Andersen solver");
                return new FieldInsensitivePolicy();
            default:
                logger.warn("Unknown field-policy option '{}', falling back to insensitive", option);
                return new FieldInsensitivePolicy();
        }
    }

    private int resolveContextDepth() {
        if (!getOptions().has("context-depth")) {
            return 0;
        }
        try {
            int depth = getOptions().getInt("context-depth");
            return Math.max(0, depth);
        } catch (RuntimeException ex) {
            logger.warn("Invalid context-depth option, falling back to 0", ex);
            return 0;
        }
    }

    private int resolveObjectDepth() {
        if (!getOptions().has("object-depth")) {
            return 0;
        }
        try {
            int depth = getOptions().getInt("object-depth");
            return Math.max(0, depth);
        } catch (RuntimeException ex) {
            logger.warn("Invalid object-depth option, falling back to 0", ex);
            return 0;
        }
    }
}
