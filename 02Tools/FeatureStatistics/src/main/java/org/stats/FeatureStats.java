package org.stats;

import org.ASTfeature.SourceCodeFeature;
import org.io.ReportWriter;
import org.model.FeatureOccurrence;
import org.model.report.FeatureReport;
import org.model.summary.DistributionEntry;
import org.model.summary.Summary;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FeatureStats extends AbstractStatModule {

    /**
     * Feature whitelist used by the empirical-study feature outputs.
     *
     * Detectors may still emit other SourceCodeFeature values because other
     * statistics modules depend on them, but summary.json, features_by_case.json,
     * and feature_by_case.json only expose the features listed here.
     */
    private static final Set<SourceCodeFeature> REPORTED_FEATURES = EnumSet.of(
            SourceCodeFeature.ifStmt,
            SourceCodeFeature.switchStmt,
            SourceCodeFeature.tryCatchStmt,
            SourceCodeFeature.loop,
            SourceCodeFeature.arithmeticOperator,
            SourceCodeFeature.shiftOperator,
            SourceCodeFeature.compareOperator,
            SourceCodeFeature.primitiveType,
            SourceCodeFeature.wrapperType,
            SourceCodeFeature.arrayType,
            SourceCodeFeature.genericType,
            SourceCodeFeature.typeParameter,
            SourceCodeFeature.wildcardType,
            SourceCodeFeature.interfaceType,
            SourceCodeFeature.abstractType,
            SourceCodeFeature.nestedType,
            SourceCodeFeature.hierarchy,
            SourceCodeFeature.lambda,
            SourceCodeFeature.reflection,
            SourceCodeFeature.methodHandle,
            SourceCodeFeature.TypeCast,
            SourceCodeFeature.primitiveConversion
    );

    private final Map<String, List<String>> featuresByCase = new LinkedHashMap<>();

    public FeatureStats() {
        super.saveFileName = "summary.json";
        for (SourceCodeFeature feature : REPORTED_FEATURES) {
            super.distribution.put(feature.name(), 0);
        }
    }

    @Override
    protected void accumulate(Object report) {
        assert report instanceof FeatureReport;

        FeatureReport caseReport = (FeatureReport) report;
        List<String> features = caseReport.getFeatures();

        for (String feature : features) {
            super.distribution.merge(feature, 1, Integer::sum);
        }

        // Preserve case-level feature information for AST x Pass analysis.
        // The summary file is suitable for distribution analysis, while this
        // file allows joining with LLM optimization-pass results.
        featuresByCase.put(caseReport.getCaseName(), new ArrayList<>(features));
    }

    @Override
    public void generateSummary(File rootDir, ReportWriter writer) {
        Summary summary = new Summary(super.totalCases, super.hasFeatureCases, buildDistributionWithPercentages());
        writer.write(summary, new File(rootDir, super.saveFileName));

        writer.write(featuresByCase, new File(rootDir, "features_by_case.json"));
    }

    private Map<String, DistributionEntry> buildDistributionWithPercentages() {
        Map<String, DistributionEntry> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : super.distribution.entrySet()) {
            int count = entry.getValue();
            result.put(entry.getKey(), new DistributionEntry(count, formatPercentage(count)));
        }
        return result;
    }

    private String formatPercentage(int count) {
        if (super.totalCases == 0) {
            return "0.00%";
        }
        return String.format("%.2f%%", count * 100.0 / super.totalCases);
    }

    public static boolean isReportedFeature(SourceCodeFeature feature) {
        return REPORTED_FEATURES.contains(feature);
    }

    @Override
    public Object getSingleReport(List<FeatureOccurrence> occurrences, File caseDir) {
        List<String> featureNames = occurrences.stream()
                .map(FeatureOccurrence::getFeature)
                .filter(FeatureStats::isReportedFeature)
                .map(SourceCodeFeature::name)
                .distinct()
                .collect(Collectors.toList());

        if (featureNames.isEmpty()) {
            return null;
        }

        return new FeatureReport(
                caseDir.getName(),
                new ArrayList<>(),
                featureNames
        );
    }
}
