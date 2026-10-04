package org.stats;

import org.io.ReportWriter;
import org.model.FeatureOccurrence;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StatsManager {

    private final List<AbstractStatModule> modules;
    private final List<Map<String, Object>> caseFeatureReports;

    public StatsManager() {
        this.modules = new ArrayList<>();
        this.caseFeatureReports = new ArrayList<>();
        // Register all statistics modules you need here.
        this.modules.add(new FeatureStats());
        this.modules.add(new TypeCastingStats());
        this.modules.add(new HierarchyStats());
        this.modules.add(new LoopStats());
    }

    public void genAllSummaries(File rootDir, ReportWriter writer) {
        for (AbstractStatModule module : modules) {
            module.generateSummary(rootDir, writer);
        }

        writer.write(caseFeatureReports, new File(rootDir, "feature_by_case.json"));
    }

    public void genCaseReport(List<FeatureOccurrence> occurrences, File caseDir, ReportWriter writer) {
        for (AbstractStatModule module : modules) {
            Object report = module.getCaseReport(occurrences, caseDir);
            if (report == null) continue;
            module.record(report);
        }

        recordCaseFeatures(occurrences, caseDir);
    }

    private void recordCaseFeatures(List<FeatureOccurrence> occurrences, File caseDir) {
        Set<String> features = new LinkedHashSet<>();
        for (FeatureOccurrence occurrence : occurrences) {
            if (FeatureStats.isReportedFeature(occurrence.getFeature())) {
                features.add(occurrence.getFeature().name());
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("issue", caseDir.getName());
        entry.put("features", new ArrayList<>(features));
        caseFeatureReports.add(entry);
    }
}
