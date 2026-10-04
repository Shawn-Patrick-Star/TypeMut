package org.model.hierarchy;

import java.util.Objects;

public class HierarchyEdge implements Comparable<HierarchyEdge> {

    public final String sourceName;
    public final NodeType sourceType;
    public final String targetName;
    public final NodeType targetType;
    public final RelationType relation;

    public HierarchyEdge(String sourceName, NodeType sourceType,
                         String targetName, NodeType targetType,
                         RelationType relation) {
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.targetName = targetName;
        this.targetType = targetType;
        this.relation = relation;
    }

    // Override toString for debugging.
    @Override
    public String toString() {
        return String.format("%s(%s) -[%s]-> %s(%s)",
                sourceName, sourceType, relation, targetName, targetType);
    }

    // Implement comparison logic so edges are ordered consistently for pattern generation.
    @Override
    public int compareTo(HierarchyEdge o) {
        int c1 = this.sourceName.compareTo(o.sourceName);
        if (c1 != 0) return c1;
        return this.targetName.compareTo(o.targetName);
    }

    // equals and hashCode are also necessary.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HierarchyEdge)) return false;
        HierarchyEdge that = (HierarchyEdge) o;
        return Objects.equals(sourceName, that.sourceName) &&
                Objects.equals(targetName, that.targetName) &&
                relation == that.relation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceName, targetName, relation);
    }
}
