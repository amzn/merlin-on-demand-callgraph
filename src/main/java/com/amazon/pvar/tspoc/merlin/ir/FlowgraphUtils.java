package com.amazon.pvar.tspoc.merlin.ir;

import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;

import java.util.*;
import java.util.stream.Stream;

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

    public static boolean isMethodCallWithStaticProperty(CallNode callNode) {
        return callNode.getFunctionRegister() == -1 && callNode.getPropertyString() != null;
    }

    private final static Map<Function, Map<AbstractNode, Set<AbstractNode>>> predecessorMapCache = Collections.synchronizedMap(new HashMap<>());

}
