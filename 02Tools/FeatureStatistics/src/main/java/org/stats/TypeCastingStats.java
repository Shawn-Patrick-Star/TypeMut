package org.stats;

import org.ASTfeature.SourceCodeFeature;
import org.io.ReportWriter;
import org.model.report.CastingReport;
import org.model.summary.CastingSummary;
import org.model.FeatureOccurrence;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TypeCastingStats extends AbstractStatModule {
    private int castNumGlobal = 0;

    public TypeCastingStats() {
        super.saveFileName = "casting_summary.json";
    }

    @Override
    protected void accumulate(Object report) {
        assert report instanceof CastingReport;

        CastingReport castingReport = (CastingReport) report;
        castNumGlobal += castingReport.getCastNum();
        for (Map.Entry<String, Integer> entry : castingReport.getDistribution().entrySet()) {
            super.distribution.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    @Override
    public void generateSummary(File rootDir, ReportWriter writer) {
        CastingSummary summary = new CastingSummary(
                super.totalCases,
                super.hasFeatureCases,
                castNumGlobal,
                super.distribution
        );
        writer.write(summary, new File(rootDir, super.saveFileName));
    }

    @Override
    public Object getSingleReport(List<FeatureOccurrence> occurrences, File caseDir) {
        List<FeatureOccurrence> conversions = occurrences.stream()
                .filter(o -> o.getFeature() == SourceCodeFeature.TypeCast)
                .collect(Collectors.toList());

        if (conversions.isEmpty()) return null;

        // The detector already prefixes details with explicit:/implicit: so the
        // aggregate TypeCast feature remains independently inspectable by form.
        Map<String, Integer> dist = new HashMap<>();
        for (FeatureOccurrence o : conversions) {
            String detail = o.getDetail();
            if (detail != null) {
                dist.merge(detail, 1, Integer::sum);
            }
        }

        return new CastingReport(
                caseDir.getName(),
                conversions.size(),
                dist
        );
    }
}
