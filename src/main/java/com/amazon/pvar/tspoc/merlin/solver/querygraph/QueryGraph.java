/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.pvar.tspoc.merlin.solver.querygraph;

import com.amazon.pvar.tspoc.merlin.solver.MerlinSolver;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;

import java.util.*;

/**
 * This class keeps track of dependence relations between a Merlin query and its subqueries.
 *
 * The main utility of this class lies in the detection and handling of cyclical query dependencies.
 *
 * Using the singleton pattern here for simplicity.
 */
public class QueryGraph {

    private static QueryGraph instance = new QueryGraph(new EmptyResolutionStrategy());
    private final CycleResolutionStrategy strategy;
    private MerlinSolver root;

    /**
     * The backing graph used here comes from Guava, but the Guava graph API is still in beta. If moved to production,
     * a different graph API would be needed.
     */
    private final MutableGraph<MerlinSolver> backingGraph = GraphBuilder
            .directed()
            .allowsSelfLoops(true)
            .build();

    private QueryGraph(CycleResolutionStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Get the singleton instance
     * @return
     */
    public static QueryGraph getInstance() {
        if (Objects.isNull(instance)) {
            instance = new QueryGraph(new EmptyResolutionStrategy());
        }
        return instance;
    }

    /**
     * Add an edge to the query graph
     * @param from
     * @param to
     */
    public void addEdge(MerlinSolver from, MerlinSolver to) {
        QueryEdge edge = new QueryEdge(from, to);
        if (doesGraphHaveCycle()) {
            try {
                strategy.resolveCycle(new Cycle(edge));
            } catch (Cycle.CycleConstructionFailedException e) {
                e.printStackTrace();
            }
        }
        backingGraph.putEdge(from, to);
    }

    /**
     * Set the root (initial query) of the query graph
     * @param root
     */
    public void setRoot(MerlinSolver root) {
        this.root = root;
        backingGraph.addNode(root);
    }

    /**
     * @return the root (initial query) of the query graph
     */
    public MerlinSolver getRoot() {
        return root;
    }

    /**
     * Set the query graph instance to null, resetting the singleton
     */
    public static void reset() {
        instance = null;
    }

    /**
     * Determine if adding the edge to the query graph would introduce a cycle. This can be checked by seeing if the
     * target vertex is already in the graph.
     *
     * @return true if the graph contains a cycle, false otherwise
     */
    private boolean doesGraphHaveCycle() {
        return backingGraph.nodes().size() != (backingGraph.edges().size() + 1);
    }

    public static class QueryEdge {

        private final MerlinSolver startVertex;
        private final MerlinSolver endVertex;

        private QueryEdge(MerlinSolver startVertex, MerlinSolver endVertex) {
            this.startVertex = startVertex;
            this.endVertex = endVertex;
        }

        public MerlinSolver getStartVertex() {
            return startVertex;
        }

        public MerlinSolver getEndVertex() {
            return endVertex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            QueryEdge queryEdge = (QueryEdge) o;
            return Objects.equals(startVertex, queryEdge.startVertex) && Objects.equals(endVertex, queryEdge.endVertex);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startVertex, endVertex);
        }
    }

    /**
     * A representation of the nodes and edges in the query graph that form a cycle
     */
    public class Cycle {
        private final QueryEdge backEdge;
        private boolean resolved = false;
        private final Set<QueryEdge> cycleEdges;

        private Cycle(QueryEdge backEdge) throws CycleConstructionFailedException {
            this.backEdge = backEdge;
            this.cycleEdges = computeCycleEdges();
        }

        /**
         * Since the back-edge has not yet been added to the graph, we can use depth-first search to find the
         * path between the start and end vertices of the graph, as there will only be one. The edges of this
         * path plus the back-edge together constitute the cycle.
         *
         * @return
         */
        private Set<QueryEdge> computeCycleEdges() throws CycleConstructionFailedException {
            Deque<MerlinSolver> vertexStack = new ArrayDeque<>();
            Deque<QueryEdge> pathStack = new ArrayDeque<>();
            vertexStack.add(backEdge.getEndVertex());
            while (!vertexStack.isEmpty()) {
                MerlinSolver start = vertexStack.pop();
                if (start.equals(backEdge.getStartVertex())) {
                    return new HashSet<>(pathStack);
                } else if (!pathStack.isEmpty()) {
                    pathStack.pop();
                }
                vertexStack.addAll(backingGraph.successors(start));
                pathStack.add(new QueryEdge(start, vertexStack.peek()));
            }
            throw new CycleConstructionFailedException();
        }

        public boolean isResolved() {
            return resolved;
        }

        public Set<QueryEdge> getCycleEdges() {
            return cycleEdges;
        }

        public void setResolved(boolean resolved) {
            this.resolved = resolved;
        }

        public String toDOTString() {
            StringBuilder sb = new StringBuilder();
            sb.append("digraph G {\n");
            cycleEdges.forEach(
                    edge -> {
                        sb.append("  \"" + edge.getStartVertex() + "\" -> \"" + edge.getEndVertex() + "\";\n");
                    }
            );
            sb.append("}");
            return sb.toString();
        }

        protected static class CycleConstructionFailedException extends Exception {}
    }

    /**
     * Return a DOT representation of the query graph
     * @return
     */
    public String toDOTString() {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph G {\n");
        backingGraph.edges().forEach(
                edge -> {
                    sb.append("  \"" + edge.source() + "\" -> \"" + edge.target() + "\";\n");
                }
        );
        sb.append("}");
        return sb.toString();
    }

}
