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
import com.amazon.pvar.tspoc.merlin.solver.BackwardMerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.amazon.pvar.tspoc.merlin.solver.ForwardMerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.MerlinSolverFactory;
import com.amazon.pvar.tspoc.merlin.solver.PointsToGraph;
import com.amazon.pvar.tspoc.merlin.solver.querygraph.QueryGraph;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.ConstantNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import org.junit.Test;
import sync.pds.solver.nodes.Node;

import java.util.Collection;

public class IntraproceduralPointsToTests extends AbstractCallGraphTest {

    @Test
    public void forwardQueryObj() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        NewObjectNode non = (NewObjectNode) getNodeByIndex(9, flowGraph);
        ObjectAllocation allocation = new ObjectAllocation(non);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(non),
                allocation
        );

        ForwardMerlinSolver solver = new ForwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = pointsTo.getKnownValuesPointingTo(allocation);

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
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        ConstantNode constantNode = (ConstantNode) getNodeByIndex(9, flowGraph);
        ConstantAllocation allocation = new ConstantAllocation(constantNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(constantNode),
                allocation
        );

        ForwardMerlinSolver solver = new ForwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = pointsTo.getKnownValuesPointingTo(allocation);

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
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        DeclareFunctionNode funcNode = (DeclareFunctionNode) getNodeByIndex(8, flowGraph);
        FunctionAllocation allocation = new FunctionAllocation(funcNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(funcNode),
                allocation
        );

        ForwardMerlinSolver solver = new ForwardMerlinSolver(callGraph, pointsTo, initialQuery);
        MerlinSolverFactory.addNewActiveSolver(solver);
        QueryGraph.getInstance().setRoot(solver);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = pointsTo.getKnownValuesPointingTo(allocation);
        Collection<CallNode> invokes = pointsTo.getKnownFunctionInvocations(allocation);

        assert invokes.contains(((CallNode) getNodeByIndex(15, flowGraph)));
        assert !invokes.contains(((CallNode) getNodeByIndex(19, flowGraph)));
    }

    @Test
    public void forwardQueryFuncDecl() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionDecl.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        DeclareFunctionNode funcNode = (DeclareFunctionNode) getNodeByIndex(1, flowGraph);
        FunctionAllocation allocation = new FunctionAllocation(funcNode);
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(funcNode),
                allocation
        );

        ForwardMerlinSolver solver = new ForwardMerlinSolver(callGraph, pointsTo, initialQuery);
        MerlinSolverFactory.addNewActiveSolver(solver);
        QueryGraph.getInstance().setRoot(solver);
        solver.solve();
        Collection<PointsToGraph.PointsToLocation> pts = pointsTo.getKnownValuesPointingTo(allocation);
        Collection<CallNode> invokes = pointsTo.getKnownFunctionInvocations(allocation);

        assert invokes.contains(((CallNode) getNodeByIndex(11, flowGraph)));
        assert !invokes.contains(((CallNode) getNodeByIndex(15, flowGraph)));
    }

    @Test
    public void backwardQueryObj() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(15, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = new BackwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<Allocation> pts = pointsTo.getPointsToSet(queryNode, queryVal);

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryObjWithOverwrite() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralObjectPropagation.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = new BackwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<Allocation> pts = pointsTo.getPointsToSet(queryNode, queryVal);

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(9, flowGraph))));
        assert pts.contains(new ObjectAllocation(((NewObjectNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryConst() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralConstPropagation.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(19, flowGraph);
        Value queryVal = new Variable("valueToQuery1", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = new BackwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<Allocation> pts = pointsTo.getPointsToSet(queryNode, queryVal);

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(9, flowGraph))));
        assert pts.contains(new ConstantAllocation(((ConstantNode) getNodeByIndex(11, flowGraph))));
    }

    @Test
    public void backwardQueryFuncAssign() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionPropagation.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(20, flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = new BackwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<Allocation> pts = pointsTo.getPointsToSet(queryNode, queryVal);

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(8, flowGraph))));
        assert pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(10, flowGraph))));
    }

    @Test
    public void backwardQueryFuncDecl() {
        FlowGraph flowGraph =
                initializeFlowgraph("src/test/resources/js/callgraph/intraprocedural-tests/intraproceduralFunctionDecl.js");
        PointsToGraph pointsTo = new PointsToGraph();
        CallGraph callGraph = new CallGraph();
        dk.brics.tajs.flowgraph.jsnodes.Node queryNode = getNodeByIndex(16, flowGraph);
        Value queryVal = new Variable("valueToQuery", flowGraph.getMain());
        Node<NodeState, Value> initialQuery = new Node<>(
                new NodeState(queryNode),
                queryVal
        );

        BackwardMerlinSolver solver = new BackwardMerlinSolver(callGraph, pointsTo, initialQuery);
        solver.solve();
        Collection<Allocation> pts = pointsTo.getPointsToSet(queryNode, queryVal);

        System.out.println("Points-To set of " + queryVal + " at " + queryNode + ":");
        System.out.println(pts);

        assert !pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(1, flowGraph))));
        assert pts.contains(new FunctionAllocation(((DeclareFunctionNode) getNodeByIndex(2, flowGraph))));
    }

}
