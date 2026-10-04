package org.model.hierarchy;

import java.util.*;
import java.util.stream.Collectors;

public class HierarchyGraph {
    private final Set<HierarchyEdge> edges = new TreeSet<>();

    public void addEdge(String src, NodeType srcType, String tgt, NodeType tgtType, RelationType rel) {
        edges.add(new HierarchyEdge(src, srcType, tgt, tgtType, rel));
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /**
     * [Core change]
     * Get all independent patterns from the current graph.
     * 1. Split the graph into disconnected components (connected components).
     * 2. Generate a normalized pattern string for each component.
     * @return a list of independent patterns (for example ["C0->I0", "C0->I0", "C0->C1"]), without deduplication.
     */
    public List<String> getPatterns() {
        if (edges.isEmpty()) return Collections.emptyList();

        List<String> resultPatterns = new ArrayList<>();

        // 1. Split into connected components: obtain the set of edges for each component.
        List<Set<HierarchyEdge>> components = splitConnectedComponents();

        // 2. Normalize each component separately.
        for (Set<HierarchyEdge> componentEdges : components) {
            resultPatterns.add(normalizeComponent(componentEdges));
        }

        return resultPatterns;
    }

    /**
     * Algorithm: split into connected components.
     */
    private List<Set<HierarchyEdge>> splitConnectedComponents() {
        // 1. Build an adjacency list (undirected, used to find connectivity)
        // Key: node name, Value: connected edges
        Map<String, List<HierarchyEdge>> adjacency = new HashMap<>();
        for (HierarchyEdge edge : edges) {
            adjacency.computeIfAbsent(edge.sourceName, k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.targetName, k -> new ArrayList<>()).add(edge);
        }

        List<Set<HierarchyEdge>> components = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();

        // 2. Traverse all involved nodes using BFS/DFS.
        for (String node : adjacency.keySet()) {
            if (visitedNodes.contains(node)) continue;

            // Discover a new connected component.
            Set<HierarchyEdge> currentComponentEdges = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(node);
            visitedNodes.add(node);

            while (!queue.isEmpty()) {
                String curr = queue.poll();
                List<HierarchyEdge> neighbors = adjacency.get(curr);

                if (neighbors != null) {
                    for (HierarchyEdge edge : neighbors) {
                        currentComponentEdges.add(edge);

                        // Find the other node connected by this edge.
                        String neighborNode = edge.sourceName.equals(curr) ? edge.targetName : edge.sourceName;

                        if (!visitedNodes.contains(neighborNode)) {
                            visitedNodes.add(neighborNode);
                            queue.add(neighborNode);
                        }
                    }
                }
            }
            components.add(currentComponentEdges);
        }
        return components;
    }

    /**
     * Algorithm: normalize a single connected component (similar to the previous getPattern logic, but scoped to the provided edges only).
     */
    private String normalizeComponent(Set<HierarchyEdge> componentEdges) {
        // The edges must be sorted to ensure deterministic ID assignment.
        List<HierarchyEdge> sortedEdges = new ArrayList<>(componentEdges);
        Collections.sort(sortedEdges);

        Map<String, String> nameToId = new HashMap<>();

        // Reset the counters for each new component normalization (C0, C1, ...).
        int cCount = 0, // Class
            iCount = 0, // Interface
            eCount = 0, // Enum
            rCount = 0, // Record
            aCount = 0, // Annotation
            uCount = 0; // Unknown

        for (HierarchyEdge edge : sortedEdges) {
            // Source
            if (!nameToId.containsKey(edge.sourceName)) {
                String id = "";
                switch (edge.sourceType) {
                    case CLASS:      id = "C" + (cCount++); break;
                    case INTERFACE:  id = "I" + (iCount++); break;
                    case ENUM:       id = "E" + (eCount++); break;
                    case RECORD:     id = "R" + (rCount++); break;
                    case ANNOTATION: id = "A" + (aCount++); break;
                    default:         id = "U" + (uCount++); break;
                }
                nameToId.put(edge.sourceName, id);
            }
            // Target
            if (!nameToId.containsKey(edge.targetName)) {
                String id = "";
                switch (edge.targetType) {
                    case CLASS:      id = "C" + (cCount++); break;
                    case INTERFACE:  id = "I" + (iCount++); break;
                    case ENUM:       id = "E" + (eCount++); break;
                    case RECORD:     id = "R" + (rCount++); break;
                    case ANNOTATION: id = "A" + (aCount++); break;
                    default:         id = "U" + (uCount++); break;
                }
                nameToId.put(edge.targetName, id);
            }
        }

        // Build the pattern string.
        List<String> patternList = new ArrayList<>();
        for (HierarchyEdge edge : sortedEdges) {
            String srcId = nameToId.get(edge.sourceName);
            String tgtId = nameToId.get(edge.targetName);
            patternList.add(srcId + "->" + tgtId);
        }

        Collections.sort(patternList);
        return String.join(", ", patternList);
    }

    // Detailed description for debugging.
    public String getConcreteDescription() {
        return edges.stream().map(HierarchyEdge::toString).collect(Collectors.joining("; "));
    }
}