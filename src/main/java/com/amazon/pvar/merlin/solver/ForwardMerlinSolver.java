/*
 * Copyright 2022-2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.pvar.merlin.solver;

import com.amazon.pvar.merlin.DebugUtils;
import com.amazon.pvar.merlin.ir.Allocation;
import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.Property;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.solver.flowfunctions.AbstractFlowFunctions;
import com.amazon.pvar.merlin.solver.flowfunctions.FlowFunctionContext;
import com.amazon.pvar.merlin.solver.flowfunctions.ForwardFlowFunctions;
import com.amazon.pvar.merlin.ir.*;
import sync.pds.solver.nodes.*;
import wpds.impl.Transition;
import wpds.impl.UnbalancedPopListener;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;

public class ForwardMerlinSolver extends MerlinSolver {

    public ForwardMerlinSolver(QueryManager queryManager, Node<NodeState, Value> initialQuery) {
        super(queryManager, initialQuery);
        DebugUtils.debug("Creating forwards solver for " + initialQuery);
        if (initialQuery.fact() instanceof Allocation) {
            registerPointsToUpdateListener(initialQuery);
        }
    }

    /**
     * Register a state listener on the final state of the automaton. If a new in-transition is added, that means
     * a new state is empty-field-stack-reachable from the initial query, and we add it to the data-flow facts for the
     * initial query.
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
                        queryManager.addPointsToFact(
                                node.stmt().getNode(),
                                node.fact(),
                                ((Allocation) initialQuery.fact())
                        );
                    }
                }
        );
    }

    @Override
    public void processPop(Node<NodeState, Value> curr, PopNode popNode) {
        UnbalancedPopListener<NodeState, INode<Value>, Weight.NoWeight> unbalancedPopListener =
                new UnbalancedPopListener<>() {
                    @Override
                    public void unbalancedPop(INode<Value> valueINode, Transition<NodeState, INode<Value>> transition, Weight.NoWeight noWeight) {
                        Value targetVal = transition.getTarget().fact();
                        // If the transition target is the same as the initial query value, the analysis should continue
                        // past the unbalanced pop because the value flows past the return stmt of the current function
                        final var shouldContinue = targetVal.equals(ForwardMerlinSolver.this.initialQuery.fact());
                        if (shouldContinue) {
                            final var targetFunc = transition.getLabel().getNode().getBlock().getFunction();
                            final var flowFunctions = makeFlowFunctions(curr);
                            final var callSitesAndQuery = flowFunctions.findInvocationsOfFunctionWithQuery(targetFunc);
                            final var callSites = callSitesAndQuery.getFirst();
                            final var callSitesQuery = callSitesAndQuery.getSecond();
                            queryManager.registerQueryDependency(initialQueryWithDirection(), callSitesQuery);
                            final var queryID = getQueryID(curr, true, true);
                            flowFunctions.continueWithSubqueryResult(callSites, queryID, callNode -> {
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

    @Override
    public String toString() {
        return "Forward" + super.toString();
    }

    @Override
    public String getQueryString() {
        return "Forward: " + initialQuery.toString();
    }

    @Override
    public QueryID getQueryID(Node<NodeState, Value> subQuery, boolean isSubQueryForward, boolean inUnbalancedPopListener, boolean resolvesAliasing) {
        return new StandardQueryID(new Query(initialQuery, true), new Query(subQuery, isSubQueryForward), inUnbalancedPopListener, resolvesAliasing);
    }

    @Override
    protected final AbstractFlowFunctions makeFlowFunctions(Node<NodeState, Value> currentPDSNode) {
        final var context = new FlowFunctionContext(currentPDSNode);
        return new ForwardFlowFunctions(this, queryManager, context);
    }

    @Override
    public final boolean isForward() { return true; }
}
