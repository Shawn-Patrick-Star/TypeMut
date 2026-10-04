package org.model.summary;

import lombok.Getter;

import java.util.Map;

@Getter
public class CastingSummary extends Summary {
    private final int castNumGlobal;
    // distribution:
    // Key: "double->int", Value: total global count

    public CastingSummary(int totalCases,
                          int hasFeatureCases,
                          int totalCastsGlobal,
                          Map<String, Integer> distribution)
    {
        super(totalCases, hasFeatureCases, distribution);
        this.castNumGlobal = totalCastsGlobal;
    }
}