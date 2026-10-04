package org.model.report;

import lombok.Getter;

import java.util.Map;

@Getter
public class CastingReport {
    private final String caseName;
    private final int castNum;
    // Key: "double->int", Value: occurrence count
    private final Map<String, Integer> distribution;

    public CastingReport(String caseName, int castNum, Map<String, Integer> distribution) {
        this.caseName = caseName;
        this.castNum = castNum;
        this.distribution = distribution;
    }

}