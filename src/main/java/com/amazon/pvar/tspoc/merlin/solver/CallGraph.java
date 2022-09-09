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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Merlin's internal call graph representation.
 *
 * The implementation includes an iterable edge set, as well as a callsite -> function multimap and a
 * function -> callsite multimap to support fast bidirectional lookup.
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
                    + callTarget.getNode().getSourceLocation().getLineNumber();
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
    public boolean addEdge(Edge newEdge) {
        boolean added = edgeSet.add(newEdge);
        if (added) {
            calleeBackingMultimap.put(newEdge.getCallSite(), newEdge.getCallTarget());
            callerBackingMultimap.put(newEdge.getCallTarget(), newEdge.getCallSite());
        }
        return added;
    }

    public boolean addEdge(CallNode callsite, Function target) {
        Edge edge = new Edge(callsite, target);
        return addEdge(edge);
    }

    /**
     * @param callSite
     * @return a collection of the functions that can be invoked from the provided CallNode
     */
    public Collection<Function> getCallTargets(CallNode callSite) {
        return calleeBackingMultimap.get(callSite);
    }

    /**
     * @param function
     * @return a collection of the callsites that can invoke the provided function
     */
    public Collection<CallNode> getCallers(Function function) {
        return callerBackingMultimap.get(function);
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

}
