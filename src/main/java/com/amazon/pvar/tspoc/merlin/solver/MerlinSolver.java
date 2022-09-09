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

import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Property;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import com.amazon.pvar.tspoc.merlin.solver.flowfunctions.AbstractFlowFunctions;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import sync.pds.solver.OneWeightFunctions;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.PushNode;
import wpds.impl.SummaryNestedWeightedPAutomatons;
import wpds.impl.Weight;
import wpds.interfaces.State;

import java.util.Objects;
import java.util.Set;

public abstract class MerlinSolver extends SyncPDSSolver<NodeState, Value, Property, Weight.NoWeight> {

    protected final CallGraph callGraph;
    protected final PointsToGraph pointsToGraph;
    protected final Node<NodeState, Value> initialQuery;

    /**
     * The SyncPDSSolver class requires WeightFunctions in the case that the analysis includes a weight domain.
     * Since we do not use a weight domain in our analysis, we just provide the default weight function implementation
     * defined in the SPDS framework. These weight functions essentially tell the framework that "this analysis does
     * not have a weight domain".
     */
    private final WeightFunctions<NodeState, Value, Property, Weight.NoWeight> fieldWeightFunction =
            new OneWeightFunctions<>(Weight.NO_WEIGHT_ONE);
    private final WeightFunctions<NodeState, Value, NodeState, Weight.NoWeight> callWeightFunction =
            new OneWeightFunctions<>(Weight.NO_WEIGHT_ONE);

    /**
     * We do not use summaries for this analysis so useCall/FieldSummaries and call/fieldSummaries are set to
     * false and default, respectively.
     *
     * We also set maxCallDepth, maxFieldDepth, and maxUnbalancedCallDepth to -1, which corresponds to unlimited depth.
     * If developing a tool for production use, these may be useful parameters to examine for the sake of performance.
     */
    public MerlinSolver(CallGraph callGraph, PointsToGraph pointsToGraph, Node<NodeState, Value> initialQuery) {
        super(
                false,
                new SummaryNestedWeightedPAutomatons<>(),
                false,
                new SummaryNestedWeightedPAutomatons<>(),
                -1,
                -1,
                -1
        );
        this.callGraph = callGraph;
        this.pointsToGraph = pointsToGraph;
        this.initialQuery = initialQuery;
    }

    public PointsToGraph getPointsToGraph() {
        return pointsToGraph;
    }

    public CallGraph getCallGraph() {
        return callGraph;
    }

    public abstract AbstractFlowFunctions getFlowFunctions();

    protected abstract void updatePointsTo(Node<NodeState, Value> node, Set<State> nextStates);

    protected void updateCallGraph(Node<NodeState, Value> node, Set<State> nextStates) {
        dk.brics.tajs.flowgraph.jsnodes.Node tajsNode = node.stmt().getNode();
        if (tajsNode instanceof CallNode callNode) {
            nextStates.forEach(state -> {
                if (state instanceof PushNode pushNode) {
                    if (pushNode.stmt() instanceof NodeState nodeState) {
                        Function nextFunction = nodeState.getNode().getBlock().getFunction();
                        if (!nextFunction.equals(tajsNode.getBlock().getFunction())) {
                            // Add a new CG edge if the current state and the next state are in different functions
                            callGraph.addEdge(callNode, nextFunction);
                        }
                    }
                }
            });
        }
    }

    @Override
    public void applyCallSummary(NodeState nodeState, Value value, NodeState stmt1, NodeState stmt2, Value fact1) {
        // Not implemented because we are not using method summaries
    }

    @Override
    public WeightFunctions<NodeState, Value, Property, Weight.NoWeight> getFieldWeights() {
        return fieldWeightFunction;
    }

    @Override
    public WeightFunctions<NodeState, Value, NodeState, Weight.NoWeight> getCallWeights() {
        return callWeightFunction;
    }

    @Override
    public void computeSuccessor(Node<NodeState, Value> node) {
        if (Objects.isNull(node.stmt().getNode())) {
            System.err.println("Warning: no predecessor statement found. " +
                    "The analysis may have reached the beginning of the program without finding an allocation site");
            return;
        }
        Set<State> nextStates = getFlowFunctions()
                .computeNextStates(node.stmt().getNode(), node.fact());
        updateCallGraph(node, nextStates);
        updatePointsTo(node, nextStates);
        nextStates.forEach(nextState -> propagate(node, nextState));
    }

    @Override
    public Property epsilonField() {
        return Property.getEpsilon();
    }

    @Override
    public Property emptyField() {
        return Property.getEmpty();
    }

    @Override
    public NodeState epsilonStmt() {
        return NodeState.getEpsilon();
    }

    /**
     * TODO: Not required for minimal working example, but will be required for more interesting analyses
     * @return
     */
    @Override
    public Property exclusionFieldWildCard(Property property) {
        return null;
    }

    @Override
    public Property fieldWildCard() {
        return Property.getWildcard();
    }

    @Override
    public String toString() {
        return "MerlinSolver{" +
                "initialQuery=" + initialQuery +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerlinSolver that = (MerlinSolver) o;
        return Objects.equals(initialQuery, that.initialQuery);
    }

    @Override
    public int hashCode() {
        return Objects.hash(initialQuery);
    }
}
