package org.model.summary;

import lombok.Getter;

import java.util.Map;

@Getter
public class Summary {
    private final int totalCases;
    private final int hasFeatureCases;
    private final Map<String, ?> distribution;

    public Summary(int totalCases, int hasFeatureCases, Map<String, ?> distribution) {
        this.totalCases = totalCases;
        this.hasFeatureCases = hasFeatureCases;
        this.distribution = distribution;
    }
}
