package pta.andersen;

import pascal.taie.config.AnalysisConfig;

/**
 * 保留原 "pta-andersen" 分析 ID 的向后兼容别名。
 */
public final class AndersenPointerAnalysisAlias extends AndersenPointerAnalysis {

    public static final String ID = "pta-andersen";

    public AndersenPointerAnalysisAlias(AnalysisConfig config) {
        super(config);
    }
}
