package org.stats;

import org.io.ReportWriter;
import org.model.FeatureOccurrence;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractStatModule {
    protected String saveFileName;
    protected int hasFeatureCases = 0;
    protected int totalCases = 0;
    protected final Map<String, Integer> distribution = new LinkedHashMap<>();

    public Object getCaseReport(List<FeatureOccurrence> occurrences, File caseDir) {
        this.totalCases++;
        return getSingleReport(occurrences, caseDir);
    }

    public void record(Object report) {
        this.hasFeatureCases++;
        accumulate(report);
    }

    public abstract Object getSingleReport(List<FeatureOccurrence> occurrences, File caseDir);

    public abstract void generateSummary(File rootDir, ReportWriter writer);


    protected abstract void accumulate(Object report);
}