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

package com.amazon.pvar.tspoc.merlin.experiments;

import com.amazon.pvar.tspoc.merlin.ir.Allocation;
import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Register;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import com.google.common.base.Stopwatch;
import dk.brics.tajs.flowgraph.BasicBlock;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.LoadNode;
import dk.brics.tajs.flowgraph.jsnodes.ReadPropertyNode;
import dk.brics.tajs.flowgraph.jsnodes.ReadVariableNode;
import dk.brics.tajs.util.Collectors;
import sync.pds.solver.nodes.Node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ExperimentUtils {

    private static final Random random = new Random();
    public static final Set<String> SINKS = new HashSet<>();

    /**
     * Returns a query for the invoked function at a call site
     * @param flowGraph
     * @return
     */
    public static Node<NodeState, Value> getRandomQuery(FlowGraph flowGraph) {
        Map<CallNode, Register> callSites = getCallSiteMap(flowGraph);
        Collection<Map.Entry<CallNode, Register>> entries = callSites.entrySet();
        Map.Entry<CallNode, Register> selection = getRandomElementFromCollection(entries);
        return new Node<>(
                new NodeState(selection.getKey()),
                selection.getValue()
        );
    }
    
    public static Set<Node<NodeState, Value>> getAllCallSiteQueries(FlowGraph flowGraph) {
        return getCallSiteMap(flowGraph)
                .entrySet()
                .stream()
                .map(entry -> new Node<>(
                            new NodeState(entry.getKey()),
                                ((Value) entry.getValue())
                        )
                )
                .collect(java.util.stream.Collectors.toSet());
    }

    public static Set<Node<NodeState, Value>> getTaintQueries(FlowGraph flowGraph) {
        readNodeSinks();
        return flowGraph.getFunctions().stream()
                .flatMap(f -> f.getBlocks().stream())
                .flatMap(b -> b.getNodes().stream())
                .filter(abstractNode -> {
                    if (abstractNode instanceof ReadVariableNode rvn) {
                        return SINKS.contains(rvn.getVariableName());
                    } else if (abstractNode instanceof ReadPropertyNode rpn) {
                        return SINKS.contains(rpn.getPropertyString());
                    }
                    return false;
                })
                .map(abstractNode -> getQueriesAtSink(((LoadNode) abstractNode)))
                .flatMap(Collection::stream)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<Node<NodeState, Value>> getQueriesAtSink(LoadNode node) {
        Function containingFunction = node.getBlock().getFunction();
        return node.getBlock().getSingleSuccessor().getNodes().stream()
                .filter(abstractNode -> abstractNode instanceof CallNode cn &&
                        (cn.getFunctionRegister() == node.getResultRegister() ||
                        SINKS.contains(cn.getPropertyString())))
                .map(abstractNode -> ((CallNode) abstractNode))
                .flatMap(cn -> {
                    Set<Node<NodeState, Value>> argQueries = new HashSet<>();
                    // Add a query for each value passed into the taint sink
                    for (int i = 0 ; i < cn.getNumberOfArgs() ; i++) {
                        argQueries.add(
                                new Node<>(
                                        new NodeState(cn),
                                        new Register(cn.getArgRegister(i), containingFunction)
                                )
                        );
                    }
                    if (SINKS.contains(cn.getPropertyString())) {
                        // if the taint sink is a property of some value, issue a query for that value
                        argQueries.add(
                                new Node<>(
                                        new NodeState(cn),
                                        new Register(cn.getFunctionRegister(), containingFunction)
                                )
                        );
                    }
                    return argQueries.stream();
                })
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Map<CallNode, Register> getCallSiteMap(FlowGraph flowGraph) {
        return flowGraph
                .getFunctions()
                .stream()
                .flatMap(function -> function.getBlocks().stream())
                .flatMap(basicBlock -> basicBlock.getNodes().stream())
                .filter(abstractNode -> abstractNode instanceof CallNode)
                .map(abstractNode -> ((CallNode) abstractNode))
                .filter(callNode -> Objects.isNull(callNode.getTajsFunctionName()) && // Must not be an internal TAJS Call
                        callNode.getSourceLocation().getKind() != SourceLocation.Kind.SYNTHETIC)
                .collect(
                        Collectors.toMap(
                                callNode -> callNode,
                                callNode -> new Register(
                                        callNode.getFunctionRegister(),
                                        callNode.getBlock().getFunction()
                                )
                        )
                );
    }

    private static <T> T getRandomElementFromCollection(Collection<T> collection) {
        return collection.stream()
                .skip(random.nextInt(collection.size()))
                .findFirst()
                .orElseThrow();
    }

    private static Function getDeclaringScope(String varName, Function usageScope) {
        Function currentScope = usageScope;
        while (Objects.nonNull(currentScope)) {
            if (
                    currentScope.getVariableNames().contains(varName) ||
                            currentScope.getParameterNames().contains(varName) ||
                            scopeDeclaresFunctionWithName(currentScope, varName)
            ) {
                return currentScope;
            }
            currentScope = currentScope.getOuterFunction();
        }
        throw new RuntimeException("Cannot get declaring scope of variable '" + varName + "' in function '" +
                usageScope + "': '" + varName + "' should not be visible in this scope.");
    }

    private static boolean scopeDeclaresFunctionWithName(Function currentScope, String functionName) {
        return currentScope.getBlocks().stream()
                .flatMap(block -> block.getNodes().stream())
                .anyMatch(node -> {
                    try {
                        return node instanceof DeclareFunctionNode &&
                                ((DeclareFunctionNode) node).getFunction().getName().equals(functionName);
                    } catch (NullPointerException e) {
                        return false;
                    }
                });
    }

    public static void printPointsTo(
            Value queryVal,
            dk.brics.tajs.flowgraph.jsnodes.Node queryNode,
            Collection<Allocation> pts
    ) {
        System.out.println("Points-To set of " + queryVal + " at " + queryNode + " in function "
                + queryNode.getBlock().getFunction().toString() +
                " (" + queryNode.getSourceLocation().getLocation() + "):");
        System.out.println(pts);
    }

    public static boolean isCallSiteQuery(Node<NodeState, Value> query) {
        if (query.stmt().getNode() instanceof CallNode callNode) {
            if (query.fact() instanceof Register register) {
                return callNode.getFunctionRegister() == register.getId();
            }
        }
        return false;
    }

    public static void readNodeSinks() {
        File nodeSinks = ExperimentOptions.getNodeSinkFile();
        try (BufferedReader reader = new BufferedReader(new FileReader(nodeSinks))) {
            SINKS.addAll(reader.lines().toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Timer<T> {
        private Map<T, Long> times = new HashMap<>();
        private Stopwatch stopwatch = Stopwatch.createUnstarted();
        private long lastElapsed = 0;

        public void start() {
            stopwatch.start();
        }

        public void reset() {
            times = new HashMap<>();
            stopwatch = Stopwatch.createUnstarted();
            lastElapsed = 0;
        }

        public void stop() {
            stopwatch.stop();
        }

        public void split(T splitLabel) {
            long current = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            long split = current - lastElapsed;
            lastElapsed = current;
            times.put(splitLabel, split);
        }

        public long getTotalElapsed() {
            return stopwatch.elapsed(TimeUnit.MILLISECONDS);
        }

        public long getSplit(T splitLabel) {
            if (!times.containsKey(splitLabel)) {
                return 0;
            }
            return times.get(splitLabel);
        }
    }

    public static class Statistics {
        private static long totalTime = 0;
        private static int totalQueries = 0;
        private static int totalFiles = 0;
        private static int cgEdgesFound = 0;
        private static int maxQueries = 0;

        public static void incrementTotalQueries() {
            totalQueries++;
        }

        public static void incrementTotalFiles() {
            totalFiles++;
        }

        public static void incrementTotalTime(long millis) {
            totalTime += millis;
        }

        public static void incrementCGEdgesFound(int edgesFound) {
            cgEdgesFound += edgesFound;
        }

        public static void setMaxQueries(int newMax) {
            maxQueries = newMax;
        }

        public static long getTotalTimeMillis() {
            return totalTime;
        }

        public static int getTotalFiles() {
            return totalFiles;
        }

        public static int getTotalQueries() {
            return totalQueries;
        }

        public static int getTotalCGEdges() {
            return cgEdgesFound;
        }

        public static int getMaxQueries() {
            return maxQueries;
        }
    }
}
