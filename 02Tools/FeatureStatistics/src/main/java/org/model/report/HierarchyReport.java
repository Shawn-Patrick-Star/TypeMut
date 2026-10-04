package org.model.report;

import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class HierarchyReport {
    private final String caseName;
    private final Map<String, Integer> hierarchyDescription;

    public HierarchyReport(String caseName, Map<String, Integer> hierarchyDescription) {
        this.caseName = caseName;
        this.hierarchyDescription = hierarchyDescription;
    }
}
