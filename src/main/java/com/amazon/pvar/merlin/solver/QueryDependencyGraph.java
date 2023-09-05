/*
 * Copyright 2022-2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.pvar.merlin.solver;

import org.jgrapht.*;
import org.jgrapht.graph.*;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.traverse.BreadthFirstIterator;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks dependencies between queries to capture which query failed to resolve correctly. */
public final class QueryDependencyGraph {

    private final Graph<QueryNode, DefaultEdge> theGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

    public synchronized QueryNode ensureVertexInGraph(QueryNode node) {
        if (!theGraph.containsVertex(node)) {
            theGraph.addVertex(node);
        }
        return node;
    }

    public synchronized void addDependency(QueryNode from, QueryNode to) {
        final var source = ensureVertexInGraph(from);
        final var dest = ensureVertexInGraph(to);
        if (!theGraph.containsEdge(source, dest)) {
            theGraph.addEdge(source, dest);
        }
    }

    private Iterator<QueryNode> reachableFrom(QueryNode start) {
        return new BreadthFirstIterator<>(theGraph, start);
    }

    public synchronized Map<Query, Set<Exception>> errorsImpactingQuery(QueryNode node) {
        final var exceptions = new HashMap<Query, Set<Exception>>();
        for (Iterator<QueryNode> it = reachableFrom(node); it.hasNext(); ) {
            var reachableNode = it.next();
            final var errors =  reachableNode.getErrors();
            if (!errors.isEmpty()) {
                exceptions.put(reachableNode.getQuery(), errors);
            }
        }
        return exceptions;
    }

    public synchronized Set<Query> directDependenciesOf(QueryNode query) {
        return theGraph.outgoingEdgesOf(query)
                .stream().map(edge -> {
                    final var dst = theGraph.getEdgeTarget(edge);
                    return dst.getQuery();
                })
                .collect(Collectors.toSet());
    }

    public synchronized String status() {
        return "query dependencies: " + theGraph.edgeSet().size();
    }

    public void visualize() {
        final var dotExporter = new DOTExporter<QueryNode, DefaultEdge>();
        dotExporter.setVertexAttributeProvider(vertex -> {
            final Map<String, Attribute> result = new HashMap<>();
            result.put("label", DefaultAttribute.createAttribute(vertex.toVertexLabel()));
            return result;
        });
        try {
            final var tempDotFile = File.createTempFile("query-dependencies", ".dot");
            dotExporter.exportGraph(theGraph, tempDotFile);
            System.out.println("DOT written to " + tempDotFile);
//            tempDotFile.deleteOnExit();
            final var tempSVG = File.createTempFile("query-dependencies", ".svg");
            final var procBuilder = new ProcessBuilder("dot", "-Tsvg", "-o" + tempSVG, tempDotFile.toString());
            System.out.println("SVG written to " + tempSVG);
            procBuilder.start().waitFor();
            java.awt.Desktop.getDesktop().open(tempSVG);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}

