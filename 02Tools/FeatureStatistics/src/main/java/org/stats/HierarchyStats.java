package org.stats;

import org.ASTfeature.SourceCodeFeature;
import org.io.ReportWriter;
import org.model.FeatureOccurrence;
import org.model.report.HierarchyReport;
import org.model.summary.HierarchySummary;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HierarchyStats extends AbstractStatModule {

    public HierarchyStats() {
        super.saveFileName = "hierarchy_summary.json";
    }

    @Override
    protected void accumulate(Object report) {
        assert report instanceof HierarchyReport;

        HierarchyReport hReport = (HierarchyReport) report;
        for (Map.Entry<String, Integer> entry : hReport.getHierarchyDescription().entrySet()) {
            super.distribution.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    @Override
    public void generateSummary(File rootDir, ReportWriter writer) {
        HierarchySummary summary = new HierarchySummary(super.totalCases, super.hasFeatureCases, super.distribution);
        writer.write(summary, new File(rootDir, super.saveFileName));
    }

    @Override
    public Object getSingleReport(List<FeatureOccurrence> occurrences, File caseDir) {
        Map<String, Integer> dist = new HashMap<>();

        boolean hasData = false;
        for (FeatureOccurrence o : occurrences) {
            if (o.getFeature() == SourceCodeFeature.hierarchy) {
                String pattern = o.getDetail(); // This is already split into the format "C0->I0".
                if (pattern != null && !pattern.isEmpty()) {
                    dist.merge(pattern, 1, Integer::sum);
                    hasData = true;
                }
            }
        }
        if (!hasData) {
            return null;
        }

        return new HierarchyReport(caseDir.getName(), dist);
    }
}