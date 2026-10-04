package org.model.summary;

import lombok.Getter;

@Getter
public class DistributionEntry {
    private final int count;
    private final String percentage;

    public DistributionEntry(int count, String percentage) {
        this.count = count;
        this.percentage = percentage;
    }
}
