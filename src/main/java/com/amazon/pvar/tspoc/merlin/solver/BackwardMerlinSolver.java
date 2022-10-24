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

import com.amazon.pvar.tspoc.merlin.DebugUtils;
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
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.State;
import wpds.interfaces.WPAStateListener;

import java.util.Objects;

public class BackwardMerlinSolver extends MerlinSolver {

    private BackwardFlowFunctions flowFunctions;

    private boolean isFunctionQuery = false;

    public BackwardMerlinSolver(QueryManager queryManager, Node<NodeState, Value> initialQuery) {
        super(queryManager, initialQuery);
        flowFunctions = new BackwardFlowFunctions(this, queryManager);
        DebugUtils.debug("Creating backwards solver for query: " + initialQuery);
        registerPointsToUpdateListener(initialQuery);
    }

    /**
     * Register a state listener on the final state of the automaton. If a new in-transition is added, that means
     * a new state is empty-field-stack-reachable from the initial query, and we add it to the points-to set for the
     * initial query if it is an allocation site
     */
    private void registerPointsToUpdateListener(Node<NodeState, Value> initialQuery) {
        this.fieldAutomaton.registerListener(
                new WPAStateListener<>(new SingleNode<>(initialQuery)) {
                    @Override
                    public void onOutTransitionAdded(
                            Transition<Property, INode<Node<NodeState, Value>>> transition,
                            Weight.NoWeight noWeight,
                            WeightedPAutomaton<Property, INode<Node<NodeState, Value>>, Weight.NoWeight> weightedPAutomaton
                    ) {}

                    @Override
                    public void onInTransitionAdded(
                            Transition<Property, INode<Node<NodeState, Value>>> transition,
                            Weight.NoWeight noWeight,
                            WeightedPAutomaton<Property, INode<Node<NodeState, Value>>, Weight.NoWeight> weightedPAutomaton
                    ) {
                        if (transition.getStart() instanceof GeneratedState) {
                            return;
                        }
                        Node<NodeState, Value> node = transition.getStart().fact();
                        dk.brics.tajs.flowgraph.jsnodes.Node tajsNode = node.stmt().getNode();
                        if (tajsNode instanceof NewObjectNode newObjectNode &&
                                node.fact() instanceof Register register) {
                            if (newObjectNode.getResultRegister() == register.getId()) {
                                ObjectAllocation objAlloc = new ObjectAllocation(newObjectNode);
                                queryManager.getPointsToGraph().addPointsToFact(
                                        initialQuery.stmt().getNode(),
                                        initialQuery.fact(), objAlloc
                                );
                            }
                        } else if (tajsNode instanceof ConstantNode constantNode &&
                                node.fact() instanceof Register register) {
                            if (constantNode.getResultRegister() == register.getId()) {
                                ConstantAllocation constantAllocation = new ConstantAllocation(constantNode);
                                queryManager.getPointsToGraph().addPointsToFact(
                                        initialQuery.stmt().getNode(),
                                        initialQuery.fact(),
                                        constantAllocation
                                );
                            }
                        } else if (tajsNode instanceof DeclareFunctionNode funcNode) {
                            if (node.fact() instanceof Register register) {
                                if (funcNode.getResultRegister() == register.getId()) {
                                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                                    queryManager.getPointsToGraph().addPointsToFact(
                                            initialQuery.stmt().getNode(),
                                            initialQuery.fact(),
                                            functionAllocation
                                    );
                                }
                            } else if (node.fact() instanceof Variable var) {
                                if (Objects.nonNull(funcNode.getFunction().getName()) &&
                                        funcNode.getFunction().getName().equals(var.getVarName())) {
                                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                                    queryManager.getPointsToGraph().addPointsToFact(
                                            initialQuery.stmt().getNode(),
                                            initialQuery.fact(),
                                            functionAllocation
                                    );
                                }
                            }
                        }
                    }
                }
        );
    }

    /**
     * If this solver is attempting to resolve callees of a call site, and it has reached a function declaration,
     * add a new call edge to the call graph
     * @param node
     * @param nextStates
     */
    @Override
    protected void updateCallGraph(Node<NodeState, Value> node, State nextState) {
        super.updateCallGraph(node, nextState);
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
                    queryManager.getCallGraph().addEdge(initialCallNode, declareFunctionNode.getFunction());
                }
            }
        }
    }

    @Override
    public AbstractFlowFunctions getFlowFunctions() {
        return flowFunctions;
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
                (valueINode, transition, noWeight) -> {
                    Value targetVal = transition.getTarget().fact();
                    // If the transition target is the same as the initial query value, the analysis should continue
                    // past the unbalanced pop because the value flows past the entry point of the current function
                    if (targetVal.equals(BackwardMerlinSolver.this.initialQuery.fact())) {
                        DebugUtils.debug("Following unbalanced pop flow for " + BackwardMerlinSolver.this.initialQuery.fact());
                        final var func = transition.getLabel().getNode().getBlock().getFunction();
                        final var callSites = getFlowFunctions().findInvocationsOfFunction(func);
                        final var queryID = getQueryID(curr, true, true);
                        getFlowFunctions().continueWithSubqueryResult(callSites, queryID, callNode -> {
                            Node<NodeState, Value> normalizedCallPop = new Node<>(
                                    new NodeState(callNode),
                                    valueINode.fact()
                            );
                            propagate(curr, normalizedCallPop);
                        });
                    } else {
                        DebugUtils.debug("Unbalanced pop with target " + targetVal + " doesn't match initialQuery: " + BackwardMerlinSolver.this.initialQuery);
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

    @Override
    public synchronized void solve() {
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

    @Override
    public String getQueryString() {
        return "Backward: " + initialQuery.toString();
    }

    @Override
    public QueryID getQueryID(Node<NodeState, Value> subQuery, boolean isSubQueryForward, boolean inUnbalancedPopListener) {
        return new QueryID(new Query(initialQuery, false), new Query(subQuery, isSubQueryForward), inUnbalancedPopListener);
    }

    @Override
    public synchronized void withFlowFunctions(AbstractFlowFunctions flowFunctions, Runnable runnable) {
        if (flowFunctions instanceof BackwardFlowFunctions backwardFlowFunctions) {
            final var oldFlowFunctions = this.flowFunctions;
            this.flowFunctions = backwardFlowFunctions;
            runnable.run();
            this.flowFunctions = oldFlowFunctions;
        } else {
            throw new RuntimeException("BUG: Tried to use non-backward flow functions with BackwardMerlinSolver");
        }
    }
}
