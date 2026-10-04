package org.stats;

import org.ASTfeature.SourceCodeFeature;
import org.io.ReportWriter;
import org.model.FeatureOccurrence;
import org.model.report.LoopReport;
import org.model.summary.LoopSummary;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LoopStats extends AbstractStatModule {

    private int loopNumGlobal = 0;
    private final Map<String, Integer> globalDepthDist = new LinkedHashMap<>();
    private final Map<String, Integer> globalCountDist = new LinkedHashMap<>();

    public LoopStats() {
        this.saveFileName = "loop_summary.json";
    }

    // Extract information from the detail field using a regex: "Depth: 2, Count: [11, 100]"
    private static final Pattern DETAIL_PATTERN = Pattern.compile("Depth: (\\d+), Count: (.+)");

    @Override
    protected void accumulate(Object report) {
        assert report instanceof LoopReport;

        LoopReport r = (LoopReport) report;
        loopNumGlobal += r.getLoopNum();

        r.getDepthDistribution().forEach((k, v) -> globalDepthDist.merge(k, v, Integer::sum));
        r.getCountDistribution().forEach((k, v) -> globalCountDist.merge(k, v, Integer::sum));
    }

    @Override
    public void generateSummary(File rootDir, ReportWriter writer) {
        LoopSummary summary = new LoopSummary(super.totalCases, super.hasFeatureCases, loopNumGlobal, globalDepthDist, globalCountDist);
        writer.write(summary, new File(rootDir, "loop_summary.json"));
    }

    @Override
    public Object getSingleReport(List<FeatureOccurrence> occurrences, File caseDir) {
        List<FeatureOccurrence> loops = occurrences.stream()
                .filter(o -> o.getFeature() == SourceCodeFeature.loop)
                .collect(Collectors.toList());

        if (loops.isEmpty()) return null;

        Map<String, Integer> depthDist = new HashMap<>();
        Map<String, Integer> countDist = new HashMap<>();

        for (FeatureOccurrence o : loops) {
            String detail = o.getDetail(); // e.g., "Depth: 2, Count: [0, 10]"
            if (detail == null) continue;

            Matcher m = DETAIL_PATTERN.matcher(detail);
            if (m.find()) {
                String depth = "Depth-" + m.group(1);
                String count = m.group(2);

                depthDist.merge(depth, 1, Integer::sum);
                countDist.merge(count, 1, Integer::sum);
            }
        }

        return new LoopReport(
            caseDir.getName(),
            loops.size(),
            depthDist,
            countDist
        );
    }
}