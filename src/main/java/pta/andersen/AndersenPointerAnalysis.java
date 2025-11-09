package pta.andersen;

import pku.PointerAnalysisResult;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.config.AnalysisConfig;

/**
 * Entry point that wires the modular Andersen solver into Taie.
 * The analysis itself delegates to {@link ModularAndersenSolver}.
 */
public final class AndersenPointerAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "pta-andersen";

    public AndersenPointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        HeapModel heapModel = new AllocationSiteBasedModel(getOptions());
        ModularAndersenSolver solver =
                new ModularAndersenSolver(heapModel, new FieldInsensitivePolicy());
        return solver.solve();
    }
}
