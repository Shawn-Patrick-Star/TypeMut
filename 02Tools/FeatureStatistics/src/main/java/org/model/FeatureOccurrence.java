package org.model;

import lombok.Getter;
import org.ASTfeature.SourceCodeFeature;
import spoon.reflect.declaration.CtElement;

@Getter
public class FeatureOccurrence {
    private final SourceCodeFeature feature;
    private final CtElement element; // Corresponding AST element (contains location information and source code).
    private final String detail;

    public FeatureOccurrence(SourceCodeFeature feature, CtElement element, String detail) {
        this.feature = feature;
        this.element = element;
        this.detail = detail;
    }

    public FeatureOccurrence(SourceCodeFeature feature, CtElement element) {
        this(feature, element, null);
    }
}