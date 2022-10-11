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

package com.amazon.pvar.tspoc.merlin;

import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.solver.*;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.*;
import org.apache.log4j.Level;
import org.junit.Ignore;
import org.junit.Test;
import sync.pds.solver.nodes.Node;

import java.util.Collection;

public class InterproceduralPointsToTests extends AbstractCallGraphTest {

    public void printPointsTo(
            Value queryVal,
            dk.brics.tajs.flowgraph.jsnodes.Node queryNode,
            Collection<Allocation> pts
    ) {
        System.out.println("Points-To set of " + queryVal + " at " + queryNode + " in function "
                + queryNode.getBlock().getFunction().toString() +
                " (" + queryNode.getSourceLocation().getLocation() + "):");
        System.out.println(pts);
    }

    public void printCallGraph(CallGraph callGraph) {
        System.out.println("Known call graph:");
        System.out.println(callGraph);
    }

    @Test
    public void findSingleCallSite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/singleCallSite.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(18, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var liveSet = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);
        Collection<Allocation> pts = liveSet.toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(7, flowGraph))));

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite = ((CallNode) getNodeByIndex(11, flowGraph));
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite, queryNode.getBlock().getFunction())
        );
        assert solver.getCallGraph().size() == 1;
    }

    @Test
    public void flowToOuterScope() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/outerScope.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(21, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(7, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite1 = ((CallNode) getNodeByIndex(10, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(14, flowGraph));
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, queryNode.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, queryNode.getBlock().getFunction())
        );
        assert solver.getCallGraph().size() == 2;
    }

    @Test
    public void findMultipleCallSites() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/multipleCallSites.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(24, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(8, flowGraph))));
        assert pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(13, flowGraph))));

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite1 = ((CallNode) getNodeByIndex(12, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(17, flowGraph));
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, queryNode.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, queryNode.getBlock().getFunction())
        );
        assert solver.getCallGraph().size() == 2;
    }

    @Test
    public void findSingleHigherOrderCallSite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/higherOrder1.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(20, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();
        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(13, flowGraph))));

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite1 = ((CallNode) getNodeByIndex(18, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(25, flowGraph));
        DeclareFunctionNode barDeclNode = (DeclareFunctionNode) getNodeByIndex(11, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, callsite2.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, barDeclNode.getFunction())
        );
        assert solver.getCallGraph().size() == 2;
    }

    @Test
    public void findSingleHigherOrderCallSiteSimplified() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/higherOrder3.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(15, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();
        printPointsTo(queryVal, queryNode, pts);

        System.out.println();
        printCallGraph(solver.getCallGraph());
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        CallNode callsite1 = ((CallNode) getNodeByIndex(14, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(21, flowGraph));
        DeclareFunctionNode barDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, callsite2.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, barDeclNode.getFunction())
        );
        assert solver.getCallGraph().size() == 2;
    }

    @Test
    public void findMultipleHigherOrderCallSites() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/higherOrder2.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(30, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();
        queryManager.scheduler().waitUntilDone();
        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(16, flowGraph)))) &&
                        pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(43, flowGraph))));

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite1 = ((CallNode) getNodeByIndex(23, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(35, flowGraph));
        CallNode callsite3 = ((CallNode) getNodeByIndex(28, flowGraph));
        DeclareFunctionNode barDeclNode = (DeclareFunctionNode) getNodeByIndex(12, flowGraph);
        DeclareFunctionNode bazDeclNode = (DeclareFunctionNode) getNodeByIndex(14, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, callsite2.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, barDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite3, callsite2.getBlock().getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, bazDeclNode.getFunction())
        );
        assert solver.getCallGraph().size() == 4;
    }

    // Ad-hoc closure logic missing
    @Test
    public void simpleClosure() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/simpleClosure.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(27, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(8, flowGraph))));
        assert pts.size() == 1;

        System.out.println();
        printCallGraph(solver.getCallGraph());
        CallNode callsite = ((CallNode) getNodeByIndex(12, flowGraph));
        DeclareFunctionNode outerDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite, outerDeclNode.getFunction())
        );
    }

    @Test
    public void multipleUsageClosure() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureMultipleUsages.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(30, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );


        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        System.out.println();
        printCallGraph(solver.getCallGraph());

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(8, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(15, flowGraph))));
        assert pts.size() == 2;

        CallNode callsite1 = ((CallNode) getNodeByIndex(11, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(14, flowGraph));
        CallNode callsite3 = ((CallNode) getNodeByIndex(18, flowGraph));
        DeclareFunctionNode outerDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        DeclareFunctionNode xDeclNode = (DeclareFunctionNode) getNodeByIndex(22, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, outerDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, xDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite3, xDeclNode.getFunction())
        );
    }

    @Test
    @Ignore // Will fail until heap manipulation is supported
    public void paramClosureMultiUse() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureParamMultipleUsage.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(31, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        System.out.println();
        printCallGraph(solver.getCallGraph());
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(8, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(16, flowGraph))));
        assert pts.size() == 2;

        CallNode callsite1 = ((CallNode) getNodeByIndex(12, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(15, flowGraph));
        CallNode callsite3 = ((CallNode) getNodeByIndex(19, flowGraph));
        DeclareFunctionNode outerDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        DeclareFunctionNode xDeclNode = (DeclareFunctionNode) getNodeByIndex(23, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, outerDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, xDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite3, xDeclNode.getFunction())
        );
    }

    @Test
    public void notInScopeClosure() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureNotInScope.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(27, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        System.out.println();
        printCallGraph(solver.getCallGraph());

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert pts.size() == 1;

        CallNode callsiteOuter = ((CallNode) getNodeByIndex(12, flowGraph));
        CallNode callsiteY = ((CallNode) getNodeByIndex(34, flowGraph));
        CallNode callsiteNewScope = ((CallNode) getNodeByIndex(15, flowGraph));
        DeclareFunctionNode outerDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        DeclareFunctionNode xDeclNode = (DeclareFunctionNode) getNodeByIndex(19, flowGraph);
        DeclareFunctionNode newScopeDeclNode = (DeclareFunctionNode) getNodeByIndex(4, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsiteOuter, outerDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsiteY, xDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsiteNewScope, newScopeDeclNode.getFunction())
        );
    }

    @Test
    public void reassignmentClosure() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureReassigned.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(28, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        System.out.println();
        printCallGraph(solver.getCallGraph());

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(13, flowGraph))));
        assert pts.size() == 1;

        CallNode callsite1 = ((CallNode) getNodeByIndex(11, flowGraph));
        CallNode callsite2 = ((CallNode) getNodeByIndex(16, flowGraph));
        DeclareFunctionNode outerDeclNode = (DeclareFunctionNode) getNodeByIndex(2, flowGraph);
        DeclareFunctionNode xDeclNode = (DeclareFunctionNode) getNodeByIndex(20, flowGraph);
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite1, outerDeclNode.getFunction())
        );
        assert solver.getCallGraph().contains(
                new CallGraph.Edge(callsite2, xDeclNode.getFunction())
        );
    }

    // ad-hoc closure handling missing
    @Test
    public void multipleContextClosure() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureMultipleContexts.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(37, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        System.out.println();
        printCallGraph(solver.getCallGraph());

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(10, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(16, flowGraph))));
        assert pts.size() == 2;
    }

    @Test
    // points-to sets for both query values contain both allocation sites, because Merlin does not currently
    // distinguish between different instances of a particular closure.
    public void multipleContextClosureCallReturn() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureCallReturnMultContexts.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(30, flowGraph);
        Value queryVal1 = new Variable("valueToQuery1", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery1 = new Node<>(
                new NodeState(queryNode),
                queryVal1
        );
        Value queryVal2 = new Variable("valueToQuery2", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery2 = new Node<>(
                new NodeState(queryNode),
                queryVal2
        );
        final var queryManager = new QueryManager();

        BackwardMerlinSolver solver1 = queryManager.getOrStartBackwardQuery(initialQuery1);
        Collection<Allocation> pts1 = solver1.getPointsToGraph().getPointsToSet(queryNode, queryVal1).toJavaSet();

        printPointsTo(queryVal1, queryNode, pts1);
        System.out.println();
        printCallGraph(solver1.getCallGraph());

        BackwardMerlinSolver solver2 = queryManager.getOrStartBackwardQuery(initialQuery2);
        Collection<Allocation> pts2 = solver2.getPointsToGraph().getPointsToSet(queryNode, queryVal2).toJavaSet();

        System.out.println();
        printPointsTo(queryVal2, queryNode, pts2);
        System.out.println();
        printCallGraph(solver2.getCallGraph());

        assert pts1.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(12, flowGraph))));

        assert pts2.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(18, flowGraph))));
    }

    @Test
    public void forwardSimpleCallToReturn() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/simpleCallToReturn.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(9, flowGraph);
        Value queryVal = new ObjectAllocation(((NewObjectNode) queryNode));
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );
        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = queryManager.getOrStartForwardQuery(initialQuery);
        Collection<PointsToGraph.PointsToLocation> ptls = solver
                .getPointsToGraph()
                .getKnownValuesPointingTo(
                        new ObjectAllocation(((NewObjectNode) queryNode))
                )
                .toJavaSet();
        System.out.println("Points-to set:");
        for (final var ptl: ptls) {
            System.out.println("- " + ptl);
        }
        dk.brics.tajs.flowgraph.jsnodes.Node endFlowNode = getNodeByIndex(18, flowGraph);
        Value endFlowVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());

        assert ptls.contains(new PointsToGraph.PointsToLocation(endFlowNode, endFlowVal));
    }

    @Test
    public void backwardSimpleReturnToCall() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/simpleCallToReturn.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(18, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();
        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void simpleReturn() {

        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/simpleCallToReturn.js");
        final var queryNode = (dk.brics.tajs.flowgraph.jsnodes.Node)
                FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                        .filter(node -> node instanceof WriteVariableNode write && write.getVariableName().equals("valueToQuery"))
                        .findFirst()
                        .orElseThrow();
        final Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        final var initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var allocNode = (NewObjectNode) FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                .filter(node -> node instanceof NewObjectNode)
                .findFirst()
                .orElseThrow();
        final var queryManager = new QueryManager();
        final var solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();
        printPointsTo(queryVal, queryNode, pts);
        assert pts.size() == 1;
        assert pts.contains(new ObjectAllocation(allocNode));
    }

    @Test
    @Ignore // Can be useful to debug race conditions
    public void repeatTestCase() {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
        for (int i = 0; i < 100; i++) {
            findMultipleHigherOrderCallSites();
            System.out.println("============================ SUCCESS ======================================");
        }
    }

    @Test
    public void interproceduralPropReadWrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/interproceduralPropReadWrite.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(22, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(13, flowGraph))));
        assert pts.size() == 1;
    }

    @Test
    public void closureParamReassign() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureParamReassign.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(36, flowGraph);
        Value queryVal = new Variable("something", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(23, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(8, flowGraph))));
        assert pts.size() == 2;
    }

    @Test
    public void closureDepth3Simple() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureDepth3-simple.js");
        final var queryNode = (dk.brics.tajs.flowgraph.jsnodes.Node)
                FlowgraphUtils.allNodesInFunction(FlowgraphUtils.getFunctionByName(flowGraph, "inner").orElseThrow())
                        .filter(node -> node instanceof ReadVariableNode read && read.getVariableName().equals("b"))
                        .findFirst()
                        .orElseThrow();
        Value queryVal = new Variable("b", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState( queryNode),
                queryVal
        );
        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrCreateBackwardSolver(initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        printCallGraph(solver.getCallGraph());

        final NewObjectNode allocNode = (NewObjectNode) FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                .filter(node -> node instanceof NewObjectNode)
                .findFirst()
                .orElseThrow();
        assert pts.contains(new ObjectAllocation(allocNode));
        assert pts.size() == 1;
    }

    @Test
    public void closureDepth3() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/closureDepth3.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(26, flowGraph);
        Value queryVal = new Variable("res", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(initialQuery);
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        printPointsTo(queryVal, queryNode, pts);
        printCallGraph(solver.getCallGraph());

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(13, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(36, flowGraph))));
        assert pts.size() == 3;
    }

    @Test
    public void recursiveQueriesTerminate() {
        FlowGraph flowGraph = initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/recursiveQueries.js");
        final var funcDecl = (DeclareFunctionNode)FlowgraphUtils.allNodesInFunction(flowGraph.getMain()).filter(node -> {
            if (node instanceof DeclareFunctionNode decl) {
                final var func = decl.getFunction();
                return func.getName() != null && func.getName().equals("f");
            } else {
                return false;
            }
        }).findFirst().orElseThrow();
        final var funcAlloc = new FunctionAllocation(funcDecl);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(funcDecl),
                funcAlloc
        );

        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = queryManager.getOrStartForwardQuery(initialQuery);
        Collection<PointsToGraph.PointsToLocation> ptls = solver
                .getPointsToGraph()
                .getKnownValuesPointingTo(funcAlloc)
                .toJavaSet();
        System.out.println("Points-to set:");
        for (final var ptl: ptls) {
            System.out.println("- " + ptl);
        }
        final var gFunc = FlowgraphUtils.getFunctionByName(flowGraph, "g").orElseThrow();
        final var callInG = (CallNode)FlowgraphUtils.allNodesInFunction(gFunc)
                .filter(node -> node instanceof CallNode call && call.getTajsFunctionName() == null)
                .findFirst()
                .orElseThrow();
        final var fFunc = funcDecl.getFunction();
        final var callInF = (CallNode)FlowgraphUtils.allNodesInFunction(fFunc)
                .filter(node -> node instanceof CallNode call && call.getTajsFunctionName() == null)
                .findFirst()
                .orElseThrow();

        assert ptls.contains(new PointsToGraph.PointsToLocation(
                callInF,
                new Register(callInF.getFunctionRegister(), fFunc)
        ));
        assert ptls.contains(new PointsToGraph.PointsToLocation(
                callInG,
                new Register(callInG.getFunctionRegister(), gFunc)
        ));
    }

    @Test
    public void resolveArgInsideCallee() {
        FlowGraph flowGraph = initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/resolveArg.js");
        final var queryNode = (dk.brics.tajs.flowgraph.jsnodes.Node)
                FlowgraphUtils.getFunctionByName(flowGraph, "bar")
                        .orElseThrow()
                        .getOrdinaryExit()
                        .getLastNode();

        final var queryValue = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState( queryNode),
                queryValue
        );

        final var queryManager = new QueryManager();
        final var solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryValue).toJavaSet();
        printPointsTo(queryValue, queryNode, pts);
        printCallGraph(solver.getCallGraph());
        final var allocNode = (NewObjectNode) FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                .filter(node -> node instanceof NewObjectNode)
                .findFirst()
                .orElseThrow();
        final var allocSite = new ObjectAllocation(allocNode);
        assert pts.size() == 1;
        assert pts.contains(allocSite);
    }

    @Test
    public void resolveArgFromOutside() {
        FlowGraph flowGraph = initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/resolveArg.js");
        final var queryNode = (dk.brics.tajs.flowgraph.jsnodes.Node)
                FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                        .filter(node -> node instanceof WriteVariableNode write && write.getVariableName().equals("result"))
                        .findFirst()
                        .orElseThrow();
        final var queryValue = new Variable("result", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState( queryNode),
                queryValue
        );

        final var queryManager = new QueryManager();
        final var solver = queryManager.getOrStartBackwardQuery(initialQuery);
        final var pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryValue).toJavaSet();
        printPointsTo(queryValue, queryNode, pts);
        printCallGraph(solver.getCallGraph());
        final var allocNode = (NewObjectNode) FlowgraphUtils.allNodesInFunction(flowGraph.getMain())
                .filter(node -> node instanceof NewObjectNode)
                .findFirst()
                .orElseThrow();
        final var allocSite = new ObjectAllocation(allocNode);
        assert pts.size() == 1;
        assert pts.contains(allocSite);
    }

}
