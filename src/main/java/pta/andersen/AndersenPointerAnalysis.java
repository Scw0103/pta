package pta.andersen;

import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pku.PointerAnalysisResult;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.config.AnalysisConfig;

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
        } else {
            logger.info("Running in context-insensitive mode");
        }
        ModularAndersenSolver solver = new ModularAndersenSolver(heapModel, fieldPolicy, contextDepth);
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
}
