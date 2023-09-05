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
import com.amazon.pvar.merlin.ir.ConstantAllocation;
import com.amazon.pvar.merlin.ir.FunctionAllocation;
import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.ObjectAllocation;
import com.amazon.pvar.merlin.ir.Property;
import com.amazon.pvar.merlin.ir.Register;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.ir.Variable;
import com.amazon.pvar.merlin.livecollections.LiveCollection;
import com.amazon.pvar.merlin.ir.*;
import com.amazon.pvar.merlin.solver.flowfunctions.AbstractFlowFunctions;
import com.amazon.pvar.merlin.solver.flowfunctions.BackwardFlowFunctions;
import com.amazon.pvar.merlin.solver.flowfunctions.FlowFunctionContext;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.ConstantNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import sync.pds.solver.nodes.*;
import wpds.impl.Transition;
import wpds.impl.UnbalancedPopListener;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;

import java.util.Objects;

public class BackwardMerlinSolver extends MerlinSolver {

    private boolean isFunctionQuery = false;

    public BackwardMerlinSolver(QueryManager queryManager, Node<NodeState, Value> initialQuery) {
        super(queryManager, initialQuery);
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
                                queryManager.addPointsToFact(
                                        initialQuery.stmt().getNode(),
                                        initialQuery.fact(), objAlloc
                                );
                            }
                        } else if (tajsNode instanceof CallNode callNode && 
                                callNode.isConstructorCall() &&
                                node.fact() instanceof Register register &&
                                register.getId() == callNode.getResultRegister() &&
                                register.getContainingFunction().equals(callNode.getBlock().getFunction())) {
                            final var objAlloc = new ObjectAllocation(callNode);
                            queryManager.addPointsToFact(initialQuery.stmt().getNode(), initialQuery.fact(),
                                    objAlloc);
                        } else if (tajsNode instanceof ConstantNode constantNode &&
                                node.fact() instanceof Register register) {
                            if (constantNode.getResultRegister() == register.getId() &&
                                    register.getContainingFunction().equals(tajsNode.getBlock().getFunction())) {
                                ConstantAllocation constantAllocation = new ConstantAllocation(constantNode);
                                queryManager.addPointsToFact(
                                        initialQuery.stmt().getNode(),
                                        initialQuery.fact(),
                                        constantAllocation
                                );
                            }
                        } else if (tajsNode instanceof DeclareFunctionNode funcNode) {
                            if (node.fact() instanceof Register register) {
                                if (funcNode.getResultRegister() == register.getId()) {
                                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                                    queryManager.addPointsToFact(
                                            initialQuery.stmt().getNode(),
                                            initialQuery.fact(),
                                            functionAllocation
                                    );
                                }
                            } else if (node.fact() instanceof Variable var) {
                                if (Objects.nonNull(funcNode.getFunction().getName()) &&
                                        funcNode.getFunction().getName().equals(var.getVarName())) {
                                    FunctionAllocation functionAllocation = new FunctionAllocation(funcNode);
                                    queryManager.addPointsToFact(
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
     * When processing a pop node in the backward analysis, we must consider that an
     * unmatched call pop might occur on
     * a valid data flow path if the value being queried flows to an outer function
     * scope.
     * The SPDS framework has a mechanism for handling this in the form of the
     * UnbalancedPopListener class.
     * The listener's unbalancedPop method is called when an unbalanced pop is
     * detected by SPDS, and if the situation
     * described above applies, Merlin propagates the interprocedural flow as if it
     * was a normal flow.
     *
     * @param curr
     * @param popNode
     */
    @Override
    public void processPop(Node<NodeState, Value> curr, PopNode popNode) {
        UnbalancedPopListener<NodeState, INode<Value>, Weight.NoWeight> unbalancedPopListener = (valueINode, transition,
                noWeight) -> {
            Value targetVal = transition.getTarget().fact();
            // If the transition target is the same as the initial query value, the analysis
            // should continue
            // past the unbalanced pop because the value flows past the entry point of the
            // current function
            if (targetVal.equals(BackwardMerlinSolver.this.initialQuery.fact())) {
                DebugUtils.debug("Following unbalanced pop flow for " + BackwardMerlinSolver.this.initialQuery.fact());
                final var func = transition.getLabel().getNode().getBlock().getFunction();
                final var flowFunctions = makeFlowFunctions(curr);
                final var callSitesAndQuery = flowFunctions.findInvocationsOfFunctionWithQuery(func);
                final var callSites = callSitesAndQuery.getFirst();
                final var callSiteQuery = callSitesAndQuery.getSecond();
                queryManager.registerQueryDependency(initialQueryWithDirection(), callSiteQuery);
                final var queryID = getQueryID(curr, true, true);
                registerInvocationFoundHandler(curr, valueINode, flowFunctions, callSites, queryID);
            } else {
                DebugUtils.debug("Unbalanced pop with target " + targetVal + " doesn't match initialQuery: "
                        + BackwardMerlinSolver.this.initialQuery);
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

    private void registerInvocationFoundHandler(Node<NodeState, Value> curr, INode<Value> valueINode, AbstractFlowFunctions flowFunctions, LiveCollection<CallNode> callSites, QueryID queryID) {
        flowFunctions.continueWithSubqueryResult(callSites, queryID, callNode -> {
            Node<NodeState, Value> normalizedCallPop = new Node<>(
                    new NodeState(callNode),
                    valueINode.fact());
            propagate(curr, normalizedCallPop);
        });
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
    public QueryID getQueryID(Node<NodeState, Value> subQuery, boolean isSubQueryForward,
            boolean inUnbalancedPopListener, boolean resolvesAliasing) {
        return new StandardQueryID(new Query(initialQuery, false), new Query(subQuery, isSubQueryForward),
                inUnbalancedPopListener, resolvesAliasing);
    }

    protected final AbstractFlowFunctions makeFlowFunctions(Node<NodeState, Value> currentPDSNode) {
        final var context = new FlowFunctionContext(currentPDSNode);
        return new BackwardFlowFunctions(this, queryManager, context);
    }

    @Override
    public final boolean isForward() { return false; }

    public final void test() {
        final var initials = this.callAutomaton.getInitialStates();

//        this.callAutomaton.isUnbalancedState(throw new RuntimeException("tbd"));
    }

//    public AbsReturnSite possibleReturnSites() {
//        Set<INode<Value>> inits = this.callAutomaton.getInitialStates();
//        this.callAutomaton.registerListener(new WPAStateListener<NodeState, INode<Value>, Weight.NoWeight>() {
//            @Override
//            public void onOutTransitionAdded(Transition<NodeState, INode<Value>> transition, Weight.NoWeight noWeight, WeightedPAutomaton<NodeState, INode<Value>, Weight.NoWeight> weightedPAutomaton) {
//
//            }
//
//            @Override
//            public void onInTransitionAdded(Transition<NodeState, INode<Value>> transition, Weight.NoWeight noWeight, WeightedPAutomaton<NodeState, INode<Value>, Weight.NoWeight> weightedPAutomaton) {
//
//            }
//        }
//        throw new RuntimeException("tbd");
//    }
}
