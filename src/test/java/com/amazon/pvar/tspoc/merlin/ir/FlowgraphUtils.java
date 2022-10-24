package com.amazon.pvar.tspoc.merlin.ir;

import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.Function;

import java.util.Optional;
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
}
