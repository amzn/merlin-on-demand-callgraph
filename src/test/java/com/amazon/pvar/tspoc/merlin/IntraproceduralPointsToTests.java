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
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.ConstantNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import org.junit.Test;
import sync.pds.solver.nodes.Node;

import java.util.Collection;
import java.util.stream.Collectors;

public class IntraproceduralPointsToTests extends AbstractCallGraphTest {

    @Test
    public void forwardQueryObj() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        NewObjectNode non = (NewObjectNode) getNodeByIndex(9, flowGraph);
        ObjectAllocation allocation = new ObjectAllocation(non);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(non),
                allocation
        );

        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = new ForwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = solver.getPointsToGraph().getKnownValuesPointingTo(allocation).toJavaSet();

        dk.brics.tajs.flowgraph.jsnodes.Node afterV1Write = getNodeByIndex(15, flowGraph);
        dk.brics.tajs.flowgraph.jsnodes.Node afterV2Write = getNodeByIndex(17, flowGraph);
        dk.brics.tajs.flowgraph.jsnodes.Node endNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Value notQueryVal = new Variable("valueToQuery2", flowGraph.getMain());

        assert pts.contains(new PointsToGraph.PointsToLocation(afterV2Write, queryVal));
        assert !pts.contains(new PointsToGraph.PointsToLocation(afterV2Write, notQueryVal));
        assert pts.contains(new PointsToGraph.PointsToLocation(afterV1Write, queryVal));
        assert !pts.contains(new PointsToGraph.PointsToLocation(endNode, queryVal));
    }

    @Test
    public void forwardQueryConst() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralConstPropagation.js");
        ConstantNode constantNode = (ConstantNode) getNodeByIndex(9, flowGraph);
        ConstantAllocation allocation = new ConstantAllocation(constantNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(constantNode),
                allocation
        );

        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = new ForwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = solver.getPointsToGraph().getKnownValuesPointingTo(allocation).toJavaSet();

        dk.brics.tajs.flowgraph.jsnodes.Node afterV1Write = getNodeByIndex(15, flowGraph);
        dk.brics.tajs.flowgraph.jsnodes.Node afterV2Write = getNodeByIndex(17, flowGraph);
        dk.brics.tajs.flowgraph.jsnodes.Node endNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Value notQueryVal = new Variable("valueToQuery2", flowGraph.getMain());

        assert pts.contains(new PointsToGraph.PointsToLocation(afterV2Write, queryVal));
        assert !pts.contains(new PointsToGraph.PointsToLocation(afterV2Write, notQueryVal));
        assert pts.contains(new PointsToGraph.PointsToLocation(afterV1Write, queryVal));
        assert !pts.contains(new PointsToGraph.PointsToLocation(endNode, queryVal));
    }

    @Test
    public void forwardQueryFuncAssign() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionPropagation.js");
        DeclareFunctionNode funcNode = (DeclareFunctionNode) getNodeByIndex(8, flowGraph);
        FunctionAllocation allocation = new FunctionAllocation(funcNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(funcNode),
                allocation
        );
        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = queryManager.getOrStartForwardQuery(initialQuery);
        Collection<CallNode> invokes = solver.getPointsToGraph().getKnownFunctionInvocations(allocation).toJavaSet();

        assert invokes.contains(((CallNode) getNodeByIndex(15, flowGraph)));
        assert !invokes.contains(((CallNode) getNodeByIndex(19, flowGraph)));
    }

    @Test
    public void forwardQueryFuncDecl() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionDecl.js");
        DeclareFunctionNode funcNode = (DeclareFunctionNode) getNodeByIndex(1, flowGraph);
        FunctionAllocation allocation = new FunctionAllocation(funcNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(funcNode),
                allocation
        );

        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = queryManager.getOrStartForwardQuery(initialQuery);
        Collection<CallNode> invokes = solver.getPointsToGraph().getKnownFunctionInvocations(allocation).toJavaSet();

        assert invokes.contains(((CallNode) getNodeByIndex(11, flowGraph)));
        assert !invokes.contains(((CallNode) getNodeByIndex(15, flowGraph)));
    }

    @Test
    public void backwardQueryObj() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(15, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryObjWithOverwrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryConst() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralConstPropagation.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(9, flowGraph))));
        assert pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryFuncAssign() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionPropagation.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(20, flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(8, flowGraph))));
        assert pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(10, flowGraph))));
    }

    @Test
    public void backwardQueryFuncDecl() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionDecl.js");
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(16, flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(queryNode, queryVal).toJavaSet();

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(1, flowGraph))));
        assert pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(2, flowGraph))));
    }

    @Test
    public void forwardQueryPropReadWrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralPropReadWrite.js");
        NewObjectNode non = (NewObjectNode) getNodeByIndex(10, flowGraph);
        ObjectAllocation allocation = new ObjectAllocation(non);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(non),
                allocation
        );

        final var queryManager = new QueryManager();
        ForwardMerlinSolver solver = new ForwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = solver.getPointsToGraph().getKnownValuesPointingTo(allocation).toJavaSet();

        dk.brics.tajs.flowgraph.jsnodes.Node endNode = getNodeByIndex(18, flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());

        System.out.println("Data-flow of " + non + " at " + endNode + ":");
        System.out.println(pts.stream().filter(ptl -> ptl.getLocation().equals(endNode)).collect(Collectors.toSet()));

        assert pts.contains(new PointsToGraph.PointsToLocation(endNode, queryVal));
    }

    @Test
    public void backwardQueryPropReadWrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralPropReadWrite.js");
        System.out.println(flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());
        dk.brics.tajs.flowgraph.jsnodes.Node endNode = getNodeByIndex(18, flowGraph);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(endNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(endNode, queryVal).toJavaSet();

        NewObjectNode non = (NewObjectNode) getNodeByIndex(10, flowGraph);
        ObjectAllocation allocation = new ObjectAllocation(non);
        NewObjectNode otherNON = ((NewObjectNode) getNodeByIndex(8, flowGraph));
        ObjectAllocation wrongAllocation = new ObjectAllocation(otherNON);

        System.out.println("Points-To set of " + queryVal + " at " + endNode + ":");
        System.out.println(pts);

        assert pts.contains(allocation);
        assert !pts.contains(wrongAllocation);
    }

    @Test
    public void backwardLoop() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/flow-function-unit-tests/loop.js");
        Value queryVal = new Variable("x", flowGraph.getMain());
        dk.brics.tajs.flowgraph.jsnodes.Node endNode = getNodeByIndex(30, flowGraph);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(endNode),
                queryVal
        );

        final var queryManager = new QueryManager();
        BackwardMerlinSolver solver = new BackwardMerlinSolver(queryManager, initialQuery);
        solver.solve();
        Collection<Allocation> pts = solver.getPointsToGraph().getPointsToSet(endNode, queryVal).toJavaSet();

        NewObjectNode non = (NewObjectNode) getNodeByIndex(8, flowGraph);
        ObjectAllocation allocation = new ObjectAllocation(non);
        NewObjectNode otherNON = ((NewObjectNode) getNodeByIndex(21, flowGraph));
        ObjectAllocation otherAlloc = new ObjectAllocation(otherNON);

        System.out.println("Points-To set of " + queryVal + " at " + endNode + ":");
        System.out.println(pts);

        assert pts.contains(allocation);
        assert pts.contains(otherAlloc);
    }

}
