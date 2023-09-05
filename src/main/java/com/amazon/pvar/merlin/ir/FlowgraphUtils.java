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

package com.amazon.pvar.merlin.ir;

import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;

import java.util.*;
import java.util.stream.Stream;

import static com.amazon.pvar.merlin.solver.flowfunctions.ForwardFlowFunctions.invertMapping;

public class FlowgraphUtils {

    public static Stream<AbstractNode> allNodes(FlowGraph flowGraph) {
        return flowGraph.getFunctions().stream().flatMap(FlowgraphUtils::allNodesInFunction);
    }

    public static Stream<AbstractNode> allNodesInFunction(Function function) {
        return function.getBlocks().stream().flatMap(block -> block.getNodes().stream());
    }

    public static Optional<Function> getFunctionByName(FlowGraph flowGraph, String functionName) {
        return flowGraph
                .getFunctions()
                .stream()
                .filter(function -> function.getName() != null && function.getName().equals(functionName))
                .findFirst();
    }

    public synchronized static Stream<Node> predecessorsOf(Node node) {
        return predecessorMapCache
                .computeIfAbsent(node.getBlock().getFunction(), FlowGraphBuilder::makeNodePredecessorMap)
                .get(node)
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode));
    }

    public synchronized static Stream<Node> successorsOf(Node node) {
        return successorMapCache
                .computeIfAbsent(
                        node.getBlock().getFunction(),
                        f -> invertMapping(FlowGraphBuilder.makeNodePredecessorMap(f)))
                .getOrDefault(node, Collections.emptySet())
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode));
    }

    public static boolean isMethodCallWithStaticProperty(CallNode callNode) {
        return callNode.getFunctionRegister() == -1 && callNode.getPropertyString() != null;
    }

    private final static Map<Function, Map<AbstractNode, Set<AbstractNode>>> predecessorMapCache = Collections.synchronizedMap(new HashMap<>());

    /**
     * Store node successor maps for each function as needed to avoid duplicating
     * computation
     */
    private final static Map<Function, Map<AbstractNode, Set<AbstractNode>>> successorMapCache =
            Collections.synchronizedMap(new HashMap<>());


    public static Stream<CallNode> allCallNodes(FlowGraph flowGraph) {
        return allNodes(flowGraph)
                .filter(node -> node instanceof CallNode)
                .map(node -> (CallNode) node);
    }

    public static Stream<AbstractNode> propertyAccessNodes(FlowGraph flowGraph) {
        return allNodes(flowGraph)
                .filter(node -> node instanceof ReadPropertyNode || node instanceof WritePropertyNode ||
                        (node instanceof CallNode callNode && callNode.getFunctionRegister() == -1 &&
                                callNode.getTajsFunctionName() == null));
    }

    private static Map<Function, Set<Node>> functionReferenceCache = Collections.synchronizedMap(
            new HashMap<>());

    public static void clearCaches() {
        functionReferenceCache.clear();
        successorMapCache.clear();
        predecessorMapCache.clear();
        readVarResolverMap.clear();
    }

    public static Set<Node> findReferencesToFunctionName(Function function) {
        return functionReferenceCache.computeIfAbsent(function, func -> {
            final var functionName = func.getName();
            if (functionName == null) {
                // unnamed function
                return new HashSet<>();
            }
            final var decl = func.getNode();
            final var references = new HashSet<Node>();
            final var queue = new ArrayDeque<Node>();
            final var visited = new HashSet<Node>();
            queue.push((Node) decl.getBlock().getFunction().getEntry().getFirstNode());
//            queue.push(decl);
            while (!queue.isEmpty()) {
                final var nextElem = queue.pop();
                if (visited.contains(nextElem)) {
                    continue;
                }
                visited.add(nextElem);

                if (nextElem instanceof ReadVariableNode readVar && readVar.getVariableName().equals(func.getName())) {
//                if (!readVar.getBlock().getFunction().isMain()) { // debug: is hitting toplevel causing the issues?
                    references.add(readVar);
//                }
                } else if (nextElem instanceof DeclareFunctionNode funcDecl) {
                    final var params = funcDecl.getFunction().getParameterNames();
                    if (!params.contains(functionName)) {
                        queue.add((Node) funcDecl.getFunction().getEntry().getFirstNode());
                    }
                }
                queue.addAll(successorsOf(nextElem).toList());
            }
            return references;
        });
    }


    private static final Map<FlowGraph, ReadVarResolver> readVarResolverMap =
            Collections.synchronizedMap(new HashMap<>());

    public static Set<Function> readVarToFunction(FlowGraph flowGraph, ReadVariableNode readVar) {
        final var resolver = readVarResolverMap.computeIfAbsent(flowGraph, fg -> new ReadVarResolver(fg));
        return resolver.resolveReadVarJ(readVar);
    }

    public static FlowGraph currentFlowGraph = null; // HACK

    public static boolean isTAJSInternal(CallNode n) {
        return n.getSourceLocation().getKind() == SourceLocation.Kind.SYNTHETIC ||
                n.getTajsFunctionName() != null;
    }

}
