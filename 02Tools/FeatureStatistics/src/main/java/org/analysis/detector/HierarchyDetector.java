package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import org.model.hierarchy.HierarchyGraph;
import org.model.hierarchy.NodeType;
import org.model.hierarchy.RelationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
public class HierarchyDetector implements FeatureDetector {

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.hierarchy;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> rootType) {
        List<FeatureOccurrence> results = new ArrayList<>();
        HierarchyGraph graph = new HierarchyGraph();

        Set<CtType<?>> allTypes = new LinkedHashSet<>();
        allTypes.add(rootType);
        allTypes.addAll(rootType.getElements(new TypeFilter<>(CtType.class)));
        boolean hasHierarchy = false;

        for (CtType<?> type : allTypes) {
            String srcName = type.getSimpleName();
            NodeType srcType = getNodeType(type);

            // 1. Handle parent classes (extends)
            CtTypeReference<?> superClass = type.getSuperclass();
            if (superClass != null && !isIgnored(superClass.getQualifiedName())) {
                // If a superclass exists, the target type is definitely a CLASS (unless it is Enum/Record, which we simplify here).
                // This ensures the pattern becomes C0->C1 rather than C0->U1.
                graph.addEdge(srcName, srcType,
                        superClass.getSimpleName(), NodeType.CLASS,
                        RelationType.EXTENDS);
                hasHierarchy = true;
            }

            // 2. Handle interfaces (implements / interface extends)
            Set<CtTypeReference<?>> interfaces = type.getSuperInterfaces();
            if (interfaces != null) {
                for (CtTypeReference<?> iface : interfaces) {
                    if (isIgnored(iface.getQualifiedName())) continue;

                    // The target type is definitely an INTERFACE.
                    RelationType rel = type.isInterface() ? RelationType.EXTENDS : RelationType.IMPLEMENTS;

                    graph.addEdge(srcName, srcType,
                            iface.getSimpleName(), NodeType.INTERFACE,
                            rel);
                    hasHierarchy = true;
                }
            }
        }

        if (hasHierarchy) {
            List<String> patterns = graph.getPatterns();
            for (String pattern : patterns) {
                // Create one FeatureOccurrence for each independent inheritance chain.
                // Note: we still pass rootType as the element here.
                // A more precise approach would be to pass the class associated with the pattern, but that would require more invasive graph lookups.
                // Since the exporter intercepts CtType and only prints its signature, passing rootType is acceptable.
                results.add(new FeatureOccurrence(SourceCodeFeature.hierarchy, rootType, pattern));
            }

        }

        return results;
    }

    private NodeType getNodeType(CtType<?> type) {
        if (type.isInterface()) return NodeType.INTERFACE;
        if (type instanceof CtEnum) return NodeType.ENUM;
        if (type instanceof CtClass) return NodeType.CLASS;
        return NodeType.UNKNOWN;
    }

    private boolean isIgnored(String qName) {
        return "java.lang.Object".equals(qName) ||
                "java.lang.Enum".equals(qName) ||
                "java.lang.Record".equals(qName);
    }
}
