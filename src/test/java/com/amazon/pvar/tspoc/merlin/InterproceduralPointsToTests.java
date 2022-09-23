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
import com.amazon.pvar.tspoc.merlin.solver.querygraph.QueryGraph;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.ConstantNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import org.junit.Ignore;
import org.junit.Test;
import sync.pds.solver.nodes.Node;

import java.util.Collection;

public class InterproceduralPointsToTests extends AbstractCallGraphTest{

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

    public void initializeQueryGraph(MerlinSolver solver) {
        MerlinSolverFactory.addNewActiveSolver(solver);
        QueryGraph.getInstance().setRoot(solver);
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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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
    @Ignore // Will fail until datalog-style solver is implemented (leads to query graph cycle)
    public void findMultipleHigherOrderCallSites() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/higherOrder2.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(30, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(16, flowGraph))));
        assert pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(43, flowGraph))));

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

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

        BackwardMerlinSolver solver1 = MerlinSolverFactory.getNewBackwardSolver(initialQuery1);
        initializeQueryGraph(solver1);
        solver1.solve();
        Collection<Allocation> pts1 = solver1.getPointsToGraph().getPointsToSet(queryNode, queryVal1);

        printPointsTo(queryVal1, queryNode, pts1);
        System.out.println();
        printCallGraph(solver1.getCallGraph());

        BackwardMerlinSolver solver2 = MerlinSolverFactory.getNewBackwardSolver(initialQuery2);
        MerlinSolverFactory.addNewActiveSolver(solver2);
        solver2.solve();
        Collection<Allocation> pts2 = solver2.getPointsToGraph().getPointsToSet(queryNode, queryVal2);

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

        ForwardMerlinSolver solver = MerlinSolverFactory.getNewForwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> ptls = solver
                .getPointsToGraph()
                .getKnownValuesPointingTo(
                        new ObjectAllocation(((NewObjectNode) queryNode))
                );

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

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    @Ignore
    public void interproceduralPropReadWrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/interprocedural-tests/interproceduralPropReadWrite.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(22, flowGraph);
        Value queryVal = new Variable("valueToQuery", queryNode.getBlock().getFunction());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        initializeQueryGraph(solver);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal);

        printPointsTo(queryVal, queryNode, pts);
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(13, flowGraph))));
        assert pts.size() == 1;
    }
}
