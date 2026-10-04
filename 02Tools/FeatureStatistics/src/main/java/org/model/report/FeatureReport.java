package org.model.report;

import lombok.Getter;

import java.util.List;

@Getter
public class FeatureReport {
    protected final String caseName;
    private final List<String> methods;
    private final List<String> features;

    public FeatureReport(String caseName, List<String> methods, List<String> features) {
        this.caseName = caseName;
        this.methods = methods;
        this.features = features;
    }
}