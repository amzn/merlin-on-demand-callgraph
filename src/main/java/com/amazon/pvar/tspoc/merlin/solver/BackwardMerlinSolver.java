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

import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.solver.flowfunctions.AbstractFlowFunctions;
import com.amazon.pvar.tspoc.merlin.solver.flowfunctions.BackwardFlowFunctions;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.ConstantNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import sync.pds.solver.nodes.*;
import wpds.impl.Transition;
import wpds.impl.UnbalancedPopListener;
import wpds.impl.Weight;
import wpds.interfaces.State;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public class BackwardMerlinSolver extends MerlinSolver {

    private final BackwardFlowFunctions flowFunctions = new BackwardFlowFunctions(callGraph);

    private boolean isFunctionQuery = false;

    public BackwardMerlinSolver(CallGraph callGraph, PointsToGraph pointsToGraph, Node<NodeState, Value> initialQuery) {
        super(callGraph, pointsToGraph, initialQuery);
    }

    /**
     * If this solver is attempting to resolve callees of a call site, and it has reached a function declaration,
     * add a new call edge to the call graph
     * @param node
     * @param nextStates
     */
    @Override
    protected void updateCallGraph(Node<NodeState, Value> node, Set<State> nextStates) {
        super.updateCallGraph(node, nextStates);
        if (isFunctionQuery) {
            if (node.stmt().getNode() instanceof DeclareFunctionNode declareFunctionNode) {
                if (node
                        .fact()
                        .equals(
                                new Variable(
                                        declareFunctionNode.getFunction().getName(),
                                        declareFunctionNode.getBlock().getFunction()
                                )
                        )
                ) {
                    dk.brics.tajs.flowgraph.jsnodes.Node initialQueryNode = initialQuery.stmt().getNode();
                    CallNode initialCallNode = (CallNode) initialQueryNode
                            .getBlock()
                            .getSingleSuccessor()
                            .getFirstNode();
                    callGraph.addEdge(initialCallNode, declareFunctionNode.getFunction());
                }
            }
        }
    }

    @Override
    public AbstractFlowFunctions getFlowFunctions() {
        return flowFunctions;
    }

    /**
     * If data flow has reached an allocation site, we update the points-to information for the original query
     *
     * @param node
     * @param nextStates
     */
    @Override
    protected void updatePointsTo(Node<NodeState, Value> node, Set<State> nextStates) {
        dk.brics.tajs.flowgraph.jsnodes.Node tajsNode = node.stmt().getNode();
        if (tajsNode instanceof NewObjectNode newObjectNode && node.fact() instanceof Register register) {
            if (newObjectNode.getResultRegister() == register.getId()) {
                ObjectAllocation objAlloc = new ObjectAllocation(newObjectNode);
                pointsToGraph.addPointsToFact(initialQuery.stmt().getNode(), initialQuery.fact(), objAlloc);
            }
        } else if (tajsNode instanceof ConstantNode constantNode && node.fact() instanceof Register register) {
            if (constantNode.getResultRegister() == register.getId()) {
                ConstantAllocation constantAllocation = new ConstantAllocation(constantNode);
                pointsToGraph.addPointsToFact(initialQuery.stmt().getNode(), initialQuery.fact(), constantAllocation);
            }
        } else if (tajsNode instanceof DeclareFunctionNode funcNode) {
            if (node.fact() instanceof Register register) {
                if (funcNode.getResultRegister() == register.getId()) {
                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                    pointsToGraph.addPointsToFact(initialQuery.stmt().getNode(), initialQuery.fact(), functionAllocation);
                }
            } else if (node.fact() instanceof Variable var) {
                if (Objects.nonNull(funcNode.getFunction().getName()) &&
                        funcNode.getFunction().getName().equals(var.getVarName())) {
                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                    pointsToGraph.addPointsToFact(initialQuery.stmt().getNode(), initialQuery.fact(), functionAllocation);
                }
            }
        }
    }

    /**
     * When processing a pop node in the backward analysis, we must consider that an unmatched call pop might occur on
     * a valid data flow path if the value being queried flows to an outer function scope.
     * The SPDS framework has a mechanism for handling this in the form of the UnbalancedPopListener class.
     * The listener's unbalancedPop method is called when an unbalanced pop is detected by SPDS, and if the situation
     * described above applies, Merlin propagates the interprocedural flow as if it was a normal flow.
     *
     * @param curr
     * @param popNode
     */
    @Override
    public void processPop(Node<NodeState, Value> curr, PopNode popNode) {
        UnbalancedPopListener<NodeState, INode<Value>, Weight.NoWeight> unbalancedPopListener =
                new UnbalancedPopListener<>() {
                    @Override
                    public void unbalancedPop(INode<Value> valueINode, Transition<NodeState, INode<Value>> transition, Weight.NoWeight noWeight) {
                        Value targetVal = transition.getTarget().fact();
                        // If the transition target is the same as the initial query value, the analysis should continue
                        // past the unbalanced pop because the value flows past the entry point of the current function
                        if (targetVal.equals(BackwardMerlinSolver.this.initialQuery.fact())) {
                            Collection<CallNode> callSites =
                                    callGraph.getCallers(transition.getLabel().getNode().getBlock().getFunction());
                            callSites.forEach(callNode -> {
                                Node<NodeState, Value> normalizedCallPop = new Node<>(
                                        new NodeState(callNode),
                                        valueINode.fact()
                                );
                                propagate(curr, normalizedCallPop);
                            });
                        }
                    }
                };
        // Apply field normal flow
        this.callAutomaton.registerUnbalancedPopListener(unbalancedPopListener);
        if (popNode instanceof CallPopNode callPopNode) {
            addNormalFieldFlow(
                    curr,
                    new Node<>(
                            ((NodeState) callPopNode.getReturnSite()),
                            ((Value) callPopNode.location())
                    )
            );
        }
        super.processPop(curr, popNode);
    }

    public void solve() {
        INode<Value> callTarget = new SingleNode<>(initialQuery.fact());
        INode<sync.pds.solver.nodes.Node<NodeState, Value>> fieldTarget = new SingleNode<>(initialQuery);
        solve(
                initialQuery,
                Property.getEmpty(),
                fieldTarget,
                initialQuery.stmt(),
                callTarget
        );
    }

    public boolean isFunctionQuery() {
        return isFunctionQuery;
    }

    public void setFunctionQuery(boolean functionQuery) {
        isFunctionQuery = functionQuery;
    }

    @Override
    public String toString() {
        return "Backward" + super.toString();
    }
}
