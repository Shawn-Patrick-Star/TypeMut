package org.model.summary;

import lombok.Getter;

import java.util.Map;

@Getter
public class HierarchySummary extends Summary {

    public HierarchySummary(int totalCases,
                            int hasFeatureCases,
                            Map<String, Integer> distribution){
        super(totalCases, hasFeatureCases, distribution);
    }
}

