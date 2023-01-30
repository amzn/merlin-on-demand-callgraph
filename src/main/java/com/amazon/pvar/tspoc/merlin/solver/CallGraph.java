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

package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.experiments.Location;
import com.amazon.pvar.tspoc.merlin.experiments.SerializableCallGraph;
import com.amazon.pvar.tspoc.merlin.experiments.SerializableCallGraphEdge;
import com.amazon.pvar.tspoc.merlin.experiments.Span;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.*;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Merlin's call graph representation.
 * <p>
 * The implementation includes an iterable edge set, as well as a callsite -> function multimap and a
 * function -> callsite multimap to support fast bidirectional lookup.
 * <p>
 * Note: This class is purely for reporting the resulting call graph at the end of the analysis.
 * To obtain callers or call sites during analysis, use getLiveKnownFunctionInvocations in PointsToGraph
 * or `resolveFunctionCallLive` in flow functions
 */
public class CallGraph implements Iterable<CallGraph.Edge> {

    /**
     * Simple representation of a call graph edge
     */
    public static class Edge {

        private final CallNode callSite;
        private final Function callTarget;

        public Edge(CallNode callSite, Function callTarget) {
            this.callSite = callSite;
            this.callTarget = callTarget;
        }

        /**
         * @return this edge's callsite, the CallNode that invokes the target
         */
        public CallNode getCallSite() {
            return callSite;
        }

        /**
         * @return this edge's call target, the function invoked by the callsite
         */
        public Function getCallTarget() {
            return callTarget;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Edge edge = (Edge) o;
            return Objects.equals(callSite, edge.callSite) && Objects.equals(callTarget, edge.callTarget);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callSite, callTarget);
        }

        @Override
        public String toString() {
            return callSite.getSourceLocation().getLineNumber() + " --> "
                    + callTarget.getNode().getSourceLocation().getLineNumber()
                    + "(" + callSite + " -> " + callTarget + ")";
        }

        private static Span toSpan(SourceLocation sourceLocation) {
            return new Span(
                new Location(sourceLocation.getLineNumber(), sourceLocation.getColumnNumber()),
                new Location(sourceLocation.getEndLineNumber(), sourceLocation.getEndColumnNumber()),
                sourceLocation.getLocation().getPath()
            );
        }

        private SerializableCallGraphEdge toSerializable() {
            return new SerializableCallGraphEdge(
                toSpan(callTarget.getSourceLocation()),
                toSpan(callSite.getSourceLocation())
            );
        }
    }

    private final Set<Edge> edgeSet = new HashSet<>();
    private final Multimap<CallNode, Function> calleeBackingMultimap = HashMultimap.create();
    private final Multimap<Function, CallNode> callerBackingMultimap = HashMultimap.create();


    /**
     * Adds a new edge to the call graph, updating all internal data structures if the edge is not already present
     *
     * @param newEdge
     * @return true if the edge was added successfully, false if the edge was already present in the call graph or
     * adding the edge failed for any other reason.
     */
    public synchronized boolean addEdge(Edge newEdge) {
        boolean added = edgeSet.add(newEdge);
        if (added) {
            calleeBackingMultimap.put(newEdge.getCallSite(), newEdge.getCallTarget());
            callerBackingMultimap.put(newEdge.getCallTarget(), newEdge.getCallSite());
        }
        return added;
    }

    public synchronized boolean addEdge(CallNode callsite, Function target) {
        Edge edge = new Edge(callsite, target);
        return addEdge(edge);
    }

    /**
     * @return the number of edges in the call graph
     */
    public int size() {
        return edgeSet.size();
    }

    /**
     * @param edge
     * @return true if the specified edge is present in the call graph, false otherwise
     */
    public boolean contains(Edge edge) {
        return edgeSet.contains(edge);
    }

    /**
     * @return an iterator over the set of edges in the call graph
     */
    @Override
    public Iterator<Edge> iterator() {
        return new Iterator<>() {

            private final Iterator<Edge> delegate = edgeSet.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Edge next() {
                return delegate.next();
            }
        };
    }

    @Override
    public String toString() {
        return edgeSet.stream()
                .map(e -> e.toString() + "\n")
                .collect(Collectors.joining())
                .strip();
    }

    /**
     * Produces a JSON representation of the call graph.
     * <p/>
     * See src/test/resources/js/callgraph/json-tests for the shape of the JSON that is produced.
     * <p>
     * Note that the edges are serialized in a non-deterministic order; any consumers of the resulting
     * JSON should treat the `edges` array as a set rather than a list.
     */
    public JsonElement toJSON() {
        final var gson = new Gson();
        return gson.toJsonTree(toSerializableCallGraph(), SerializableCallGraph.class);
    }

    public SerializableCallGraph toSerializableCallGraph() {
        final var serializedEdges = edgeSet
            .stream()
            .map(Edge::toSerializable)
            .collect(Collectors.toSet());
        return new SerializableCallGraph(serializedEdges);
    }

    public Set<Edge> edgeSet() {
        return edgeSet;
    }
}
