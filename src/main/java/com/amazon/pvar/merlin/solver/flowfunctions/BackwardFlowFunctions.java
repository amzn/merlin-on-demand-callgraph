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

package com.amazon.pvar.merlin.solver.flowfunctions;

import com.amazon.pvar.merlin.DebugUtils;
import com.amazon.pvar.merlin.ir.FlowgraphUtils;
import com.amazon.pvar.merlin.ir.FunctionAllocation;
import com.amazon.pvar.merlin.ir.MethodCall;
import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.ObjectAllocation;
import com.amazon.pvar.merlin.ir.Property;
import com.amazon.pvar.merlin.ir.Register;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.ir.Variable;
import com.amazon.pvar.merlin.solver.MerlinSolver;
import com.amazon.pvar.merlin.solver.QueryManager;
import com.amazon.pvar.merlin.ir.*;
import com.amazon.pvar.merlin.solver.HandlerStats;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.*;

import java.util.*;

public class BackwardFlowFunctions extends AbstractFlowFunctions {

    public BackwardFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager, FlowFunctionContext context) {
        super(containingSolver, queryManager, context);
    }

    @Override
    public Collection<Node> nextNodes(Node n) {
        return getPredecessors(n);
    }

    /**
     * Kill flow of the computation result at binary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(BinaryOperatorNode n) {
        final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        if (!context.queryValue().equals(resultReg)) {
            addNormalFlowToPreds(n);
        } else {
            // Overapproximate by adding flows to both arguments, since
            // we don't model the actual operator semantics and tracking value flows through
            // operators is needed for taint tracking examples in our case study.
            final var arg1 = new Register(n.getArg1Register(), n.getBlock().getFunction());
            final var arg2 = new Register(n.getArg2Register(), n.getBlock().getFunction());
            genSingleNormalFlow(n, arg1);
            genSingleNormalFlow(n, arg2);
        }
    }

    /**
     * Update the register map with registers discovered at this node and propagate
     * the value
     *
     * @param n
     */
    @Override
    public void visit(CallNode n) {
        /*
         * Special case: desugar method call resolution into field reads.
         * Note that this logic should not really be handled in the flow function for
         * CallNode, but in its predecessor,
         * since we only create `MethodCall` facts when resolving method calls
         * backwards. However, since we
         * would have to remember to perform this logic for all other transfer
         * functions, it seems less error-prone,
         * albeit confusing, to handle it here, as MethodCall facts are only introduced
         * in resolveFunctionCall.
         */
        if (context.queryValue() instanceof MethodCall methodCall && containingSolver != null) {
            final var syntheticReadResultRegister = syntheticRegisterForMethodCall(n);
            // Add a flow from the synthetic result register into the method call at n
            final sync.pds.solver.nodes.Node<NodeState, Value> syntheticRegisterState = new sync.pds.solver.nodes.Node<>(
                    makeNodeState(n), syntheticReadResultRegister);
            containingSolver.propagate(context.currentPDSNode(), syntheticRegisterState);
            final var baseRegister = new Register(
                    methodCall.getCallNode().getBaseRegister(),
                    methodCall.getCallNode().getBlock().getFunction());
            handleFlowToFieldRead(n, baseRegister, new Property(methodCall.getCallNode().getPropertyString()),
                    syntheticRegisterState, context);
            return;
        }
        // propagate across the call site
        final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        if (!resultReg.equals(context.queryValue())) {
            addNormalFlowToPreds(n);
        }

        // If this is a call to an internal TAJS function, don't try to resolve it
        if (Objects.nonNull(n.getTajsFunctionName()) || n.getSourceLocation().getKind() == SourceLocation.Kind.SYNTHETIC) {
            return;
        }

        // If the call is a constructor call, kill the flow if the query value matches
        if (n.isConstructorCall() &&
                n.getResultRegister() != 1 &&
                context.queryValue() instanceof Register reg &&
                reg.getId() == n.getResultRegister() &&
                reg.getContainingFunction().equals(n.getBlock().getFunction())) {
            return;
        }

        // propagate the assigned value to the return value of possibly invoked
        // functions, if necessary
        if (context.queryValue().equals(resultReg) ||
                context.queryValue() instanceof ObjectAllocation) {
            final var targetFunctionsAndQueries = resolveFunctionCallWithQueries(n, queryManager);
            final var targetFunctions = targetFunctionsAndQueries.getFirst();
            final var targetFunctionQueries = targetFunctionsAndQueries.getSecond();
            final var currentSPDSNode = context.currentPDSNode();
            final var queryValue = context.queryValue();
            if (containingSolver != null) {
                final var queryID = containingSolver.getQueryID(currentSPDSNode, false, false);
                targetFunctionQueries.forEach(subquery ->
                        queryManager.registerQueryDependency(containingSolver.initialQueryWithDirection(), subquery)
                );
                continueWithSubqueryResult(targetFunctions, queryID, (targetFunction) -> {
                    DebugUtils.debug("Discovered new callee for " + n + ": " + targetFunction);
                    final var returnNode = ((Node) targetFunction.getOrdinaryExit().getLastNode());
                    final var valueToPropagateTo = (queryValue instanceof ObjectAllocation) ? queryValue
                            : new Register(1, targetFunction);
                    final var nextState = callPushState(returnNode, valueToPropagateTo, n);
                    DebugUtils.debug("Propagating to callee: " + nextState + " for node: " + currentSPDSNode);
                    containingSolver.propagate(currentSPDSNode, nextState);
                    DebugUtils.debug("Done propagating to callee");
                });
            }
        }

    }

    /**
     * TODO: Exceptional flow is not currently tracked
     * 
     * @param n
     */
    @Override
    public void visit(CatchNode n) {
        treatAsNop(n);
    }

    /**
     * Add the result register to the map of known registers and kill flow, unless
     * the constant node denotes the start
     * of the current procedure. If we are at the start of the procedure, handle the
     * interprocedural flow.
     *
     * @param n
     */
    @Override
    public void visit(ConstantNode n) {
        if (n.getResultRegister() == 1 && (context.queryValue() instanceof Variable ||
                context.queryValue() instanceof ObjectAllocation)) {
            handleflowToFunctionEntry(n, context.queryValue(), context);
        }

        final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        if (!context.queryValue().equals(resultReg)) {
            addNormalFlowToPreds(n);
        }
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(DeletePropertyNode n) {
        treatAsNop(n);
    }

    /**
     * Merlin does not handle "with" statements, but does flag this unsoundness
     * 
     * @param n
     */
    @Override
    public void visit(BeginWithNode n) {
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO: Exceptional flow is not currently tracked
     * 
     * @param n
     */
    @Override
    public void visit(ExceptionalReturnNode n) {
        treatAsNop(n);
    }

    /**
     * If this is a top-level function declaration (i.e. we are in main and the
     * function declaration does not
     * assign to a register), kill flow at the function variable.
     *
     * Otherwise, kill flow of the result register at the newly created function
     *
     * @param n
     */
    @Override
    public void visit(DeclareFunctionNode n) {
        // If a function declaration does not assign to a register, the result register
        // is -1
        if (n.getResultRegister() == -1) {
            Variable newVar = new Variable(
                    n.getFunction().getName(),
                    getDeclaringScope(n.getFunction().getName(), n.getBlock().getFunction()));
            if (!context.queryValue().equals(newVar)) {
                addNormalFlowToPreds(n);
            }
        } else {
            final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
            if (!context.queryValue().equals(resultReg)) {
                addNormalFlowToPreds(n);
            }
        }
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(BeginForInNode n) {
        treatAsNop(n);
    }

    /**
     * Add the condition register to the set of known registers and propagate all
     * normal flows. Note that branching
     * is handled by the predecessor relation.
     *
     * @param n
     */
    @Override
    public void visit(IfNode n) {
        treatAsNop(n);
    }

    /**
     * Merlin does not handle "with" statements, but does flag this unsoundness
     * 
     * @param n
     */
    @Override
    public void visit(EndWithNode n) {
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * Kill flow of the result register at the newly created object
     *
     * @param n
     */
    @Override
    public void visit(NewObjectNode n) {
        final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        if (!context.queryValue().equals(resultReg)) {
            addNormalFlowToPreds(n);
        }
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(NextPropertyNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(HasNextPropertyNode n) {
        treatAsNop(n);
    }

    /**
     * Propagate dataflow through the nop.
     *
     * @param n
     */
    @Override
    public void visit(NopNode n) {
        treatAsNop(n);
    }

    /**
     * Kills flow for the assigned value, adds a property push rule for the value
     * read from the property, and
     * propagates all other values
     * 
     * @param n
     */
    @Override
    public void visit(ReadPropertyNode n) {
        final var result = new Register(n.getResultRegister(), n.getBlock().getFunction());
        final var baseRegister = new Register(n.getBaseRegister(), n.getBlock().getFunction());
        if (n.isPropertyFixed()) {
            // Property is a fixed String
            Property property = new Property(n.getPropertyString());
            if (!context.queryValue().equals(result)) {
                addNormalFlowToPreds(n);
            }
            if (context.queryValue().equals(result)) {
                handleFlowToFieldRead(n, baseRegister, property, context.currentPDSNode(), context);
            }
        } else {
            // TODO: dispatch a backward query on the register used for the property read
            treatAsNop(n);
        }
    }

    private void handleFlowToFieldRead(Node location, Register baseRegister, Property property,
            sync.pds.solver.nodes.Node<NodeState, Value> sourceState, FlowFunctionContext context) {
        withAllocationSitesOf(location, baseRegister, alloc -> {
            assert containingSolver != null;
            getPredecessors(location)
                    .forEach(pred -> {
                        final var pushNode = new PushNode<>(
                                makeNodeState(pred),
                                alloc,
                                property,
                                SyncPDSSolver.PDSSystem.FIELDS);
                        containingSolver.propagate(sourceState, pushNode);
                    });
        }, context.queryValue());
    }

    /**
     * Propagate data from the result register to the read variable, killing the
     * variable's previous flow
     *
     * @param n
     */
    @Override
    public void visit(ReadVariableNode n) {
        Set<Value> killed = new HashSet<>();
        Register resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        Register baseReg = new Register(n.getResultBaseRegister(), n.getBlock().getFunction());
        Variable read = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction()));
        killed.add(resultReg);
        killed.add(baseReg);
        if (!killed.contains(context.queryValue())) {
            addNormalFlowToPreds(n);
        }
        // Add register -> variable flow if necessary
        if (context.queryValue().equals(resultReg)) {
            assert containingSolver != null;
            // If we can statically resolve this name to a function, then we can stop
            // analyzing further at this point
            final var resolvedToFuncs = FlowgraphUtils.readVarToFunction(FlowgraphUtils.currentFlowGraph, n);
            if (!resolvedToFuncs.isEmpty()) {
                resolvedToFuncs.forEach(func -> {
                    final var funcDecl = func.getNode();
                    final var funcAlloc = new FunctionAllocation(funcDecl);
                    queryManager.addPointsToFact(
                            containingSolver.initialQuery.stmt().getNode(),
                            containingSolver.initialQuery.fact(),
                            funcAlloc
                    );
                });
            } else {
                genSingleNormalFlow(n, read);
            }
        } else if (context.queryValue().equals(baseReg)) {
            genSingleNormalFlow(n, read);
        }
    }

    /**
     * The backward flow function across a return node is essentially the same as a
     * nop node:
     * all known values in the scope are propagated across with normal flow.
     * 
     * @param n
     */
    @Override
    public void visit(ReturnNode n) {
        treatAsNop(n);
    }

    /**
     * TODO: Exceptional flow is not currently tracked
     * 
     * @param n
     */
    @Override
    public void visit(ThrowNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(TypeofNode n) {
        treatAsNop(n);
    }

    /**
     * Kill flow of the computation result at unary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(UnaryOperatorNode n) {
        final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
        if (!context.queryValue().equals(resultReg)) {
            addNormalFlowToPreds(n);
        } else {
            final var argRegister = new Register(n.getArgRegister(), n.getBlock().getFunction());
            genSingleNormalFlow(n, argRegister);
        }
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(DeclareVariableNode n) {
        treatAsNop(n);
    }

    /**
     * Adds a property pop rule for the value written to the property, and
     * propagates all other values
     * 
     * @param n
     */
    @Override
    public void visit(WritePropertyNode n) {
        final var valueRegister = new Register(n.getValueRegister(), n.getBlock().getFunction());
        final var baseRegister = new Register(n.getBaseRegister(), n.getBlock().getFunction());
        if (n.isPropertyFixed()) {
            // Property is a fixed String
            Property property = new Property(n.getPropertyString());
            treatAsNop(n); // adds normal flows for things not affected by heap write.
            final var queryValue = context.queryValue();
            if (queryValue instanceof ObjectAllocation) {
                withAllocationSitesOf(n, baseRegister, alloc -> {
                    if (queryValue.equals(alloc)) {
                        getPredecessors(n)
                                .forEach(pred -> {
                                    final var popNode = new PopNode<>(
                                            new NodeWithLocation<>(
                                                    makeNodeState(pred),
                                                    valueRegister,
                                                    property),
                                            SyncPDSSolver.PDSSystem.FIELDS);
                                    assert containingSolver != null;
                                    final var sourceNode = new sync.pds.solver.nodes.Node<>(makeNodeState(n),
                                            (Value) alloc);
                                    containingSolver.propagate(sourceNode, popNode);
                                });
                    }
                }, queryValue);
            }
        } else {
            // TODO: dispatch a backward query on the register used for the property read
            treatAsNop(n);
        }
    }

    /**
     * Propagate data from the written variable to the argument register, killing
     * the register's previous flow.
     *
     * @param n
     */
    @Override
    public void visit(WriteVariableNode n) {
        Register argRegister = new Register(n.getValueRegister(), n.getBlock().getFunction());
        Variable write = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction()));
        if (!context.queryValue().equals(write)) {
            addNormalFlowToPreds(n);
        }
        // Add register -> variable flow if necessary
        if (context.queryValue().equals(write)) {
            genSingleNormalFlow(n, argRegister);
        }
    }

    /**
     * Ignore event dispatcher nodes and log potential unsoundness
     *
     * @param n
     */
    @Override
    public void visit(EventDispatcherNode n) {
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(EndForInNode n) {
        treatAsNop(n);
    }

    /**
     * No need to do anything special to handle begin loop nodes for backward
     * analysis.
     * 
     * @param n
     */
    @Override
    public void visit(BeginLoopNode n) {
        treatAsNop(n);
    }

    /**
     * No need to do anything special to handle end loop nodes for backward
     * analysis.
     * 
     * @param n
     */
    @Override
    public void visit(EndLoopNode n) {
        treatAsNop(n);
    }

    @Override
    protected void treatAsNop(Node n) {
        getPredecessors(n).forEach(this::addStandardNormalFlow);
    }

    private void addNormalFlowToPreds(Node n) {
        getPredecessors(n)
                .forEach(this::addStandardNormalFlow);
    }

    @Override
    protected void genSingleNormalFlow(Node n, Value v) {
        getPredecessors(n)
                .forEach(predecessor -> addSingleState(predecessor, v));
    }

    private void handleflowToFunctionEntry(ConstantNode entryNode, Value queryVal,
            FlowFunctionContext context) {
        DebugUtils.debug("Handling flow to entry of " + entryNode.getBlock().getFunction() + " looking backwards for "
                + queryVal);
        Function containingFunction = entryNode.getBlock().getFunction();
        if (containingFunction.isMain()) {
            return;
        }
        if (containingSolver != null) {
            /*
            * Only look for function invocations if the call automaton isn't unbalanced (i.e. it abstracts an unknown
            * call stack), rather than reaching the function entry when analyzing a call.
            * */

            // final var isUnbalanced =
            // ((BackwardMerlinSolver)containingSolver).possibleReturnSites();
            // final var callAut = containingSolver.getCallAutomaton();
            // containingSolver.getCallAutomaton().registerListener(new
            // WPAStateListener<NodeState, INode<Value>, Weight.NoWeight>(
            // new SingleNode<>(context.queryValue()) // this.state
            // ) {
            // @Override
            // public void onOutTransitionAdded(Transition<NodeState, INode<Value>>
            // transition, Weight.NoWeight noWeight, WeightedPAutomaton<NodeState,
            // INode<Value>, Weight.NoWeight> weightedPAutomaton) {
            // }
            //
            // @Override
            // public void onInTransitionAdded(Transition<NodeState, INode<Value>>
            // transition, Weight.NoWeight noWeight, WeightedPAutomaton<NodeState,
            // INode<Value>, Weight.NoWeight> weightedPAutomaton) {
            //
            // }
            // });

            // Consult ad-hoc call stack abstraction to figure out if we need to actually issue a query to find
            // invocations:
            final var queryID = containingSolver.getQueryID(context.currentPDSNode(), true, false);
            final var liveInvokesAndQuery = findInvocationsOfFunctionWithQuery(containingFunction);
            final var liveInvokes = liveInvokesAndQuery.getFirst();
            final var invokesQuery = liveInvokesAndQuery.getSecond();
            final var currentSPDSNode = context.currentPDSNode();
            if (queryVal instanceof Variable queryVar) {
                Optional<String> paramName = containingFunction.getParameterNames().stream()
                        .filter(name -> name.equals(queryVar.getVarName()))
                        .findFirst();
                // Launch a forward query on the containing function to find possible
                // invocations
                if (paramName.isPresent()) {
                    // continue the backward query from the argument passed to the invocation
                    int paramIndex = containingFunction.getParameterNames().indexOf(paramName.get());
                    // If queryVal is parameter name, go back to invocation site
                    queryManager.registerQueryDependency(containingSolver.initialQueryWithDirection(), invokesQuery);
                    continueWithSubqueryResult(liveInvokes, queryID, invoke -> {
                        try {
                            Register reg = new Register(invoke.getArgRegister(paramIndex),
                                    invoke.getBlock().getFunction());
                            final var bla = entryNode.getBlock().getFunction();
                            DebugUtils.debug("handleflowToFunctionEntry[param]: found invocation of " + containingFunction +
                                    ": " + invoke + " for query: " + queryVal);

                            final var invokePreds = FlowgraphUtils.predecessorsOf(invoke);
                            invokePreds.forEach(invokePred -> {
//                                final var popState = callPopState(invokePred, reg);
                                 final var popState = makeSPDSNode(invoke, reg);
                                DebugUtils.debug("Propagating pop state: " + popState + " where initialQuery=" +
                                        containingSolver.initialQuery + " and currentSPDSNode: " + context.currentPDSNode());
                                containingSolver.propagate(context.currentPDSNode(), popState);
                                DebugUtils.debug("Done propagating: " + popState);
                            });
                        } catch (ArrayIndexOutOfBoundsException ignored) {
                        }
                    });
                } else {
                    queryManager.registerQueryDependency(
                            containingSolver.initialQueryWithDirection(),
                            invokesQuery
                    );
                    continueWithSubqueryResult(liveInvokes, queryID, invoke -> {
                        DebugUtils.debug("handleflowToFunctionEntry[non-param]: found invocation of " +
                                containingFunction + ": " + invoke + " for query: " + queryVal);
                        Function invokeScope = invoke.getBlock().getFunction();
                        // otherwise, if queryVal is not visible at call site, it must have been
                        // captured in a closure
                        // and we instead go back to the end of the defining function.
                        if (!queryVar.isVisibleIn(invokeScope)) {
                            Node outerScopeReturnNode = ((Node) containingFunction.getNode().getBlock().getFunction()
                                    .getOrdinaryExit().getFirstNode());
                            final var state = makeSPDSNode(outerScopeReturnNode, queryVar);
//                            final var state = callPopState(outerScopeReturnNode, queryVar);
                            containingSolver.propagate(currentSPDSNode, state);
                        } else {
                            final var popState = callPopState(invoke, queryVar);
                            containingSolver.propagate(currentSPDSNode, popState);
                        }
                    });
                }
            } else if (queryVal instanceof ObjectAllocation alloc) {
                /*
                 * If our target query is on an object allocation (introduced via field
                 * reads/writes), then
                 * we cannot determine whether the object allocation was reached through a call
                 * site or a variable
                 * capture in the surrounding scope. In this case, we treat both as possible
                 * predecessors.
                 */
                queryManager.registerQueryDependency(
                        containingSolver.initialQueryWithDirection(),
                        invokesQuery
                );
                continueWithSubqueryResult(liveInvokes, queryID, invoke -> {
                    final var nodeAtSurroundingScope = ((Node) containingFunction.getNode().getBlock().getFunction()
                            .getOrdinaryExit().getFirstNode());
                    final var stateAtSurroundingScope = this.makeSPDSNode(nodeAtSurroundingScope, queryVal);
                    containingSolver.propagate(currentSPDSNode, stateAtSurroundingScope);
                    final var invokePreds = FlowgraphUtils.predecessorsOf(invoke);
                    invokePreds.forEach(invokePred -> {
                        final var stateAtCallSite = callPopState(invokePred, queryVal);
                        containingSolver.propagate(currentSPDSNode, stateAtCallSite);
                    });
//                    final var stateAtCallSite = callPopState(invoke, queryVal);
//                    containingSolver.propagate(currentSPDSNode, stateAtCallSite);
                });
            } else {
                throw new RuntimeException("BUG: Unexpected query value at function entry: " + queryVal);
            }
        }
    }

    @Override
    public void handleUnresolvedCall() {
        assert containingSolver != null;
        final var node = context.currentPDSNode().stmt().getNode();
        if (node instanceof CallNode callNode) {
            if (callNode.getResultRegister() != -1 &&
                    context.queryValue() instanceof Register reg &&
                    reg.getId() == callNode.getResultRegister() &&
                    reg.getContainingFunction().equals(callNode.getBlock().getFunction())) {
                // Propagate to each argument to capture dependency of function result on input
                for (int i = 0; i < callNode.getNumberOfArgs(); i++) {
                    final var argRegister = new Register(callNode.getArgRegister(i), callNode.getBlock().getFunction());
                    getPredecessors(callNode)
                            .forEach(pred -> {
                                final var nextState = makeSPDSNode(pred, argRegister);
                                containingSolver.propagate(context.currentPDSNode(), nextState);
                            });
                }
                // If this is a method call, also add a flow from the base register into the result,
                // capturing methods on primitive values
                if (FlowgraphUtils.isMethodCallWithStaticProperty(callNode)) {
                    final var baseRegister = new Register(callNode.getBaseRegister(), callNode.getBlock().getFunction());
                    getPredecessors(callNode)
                            .forEach(pred -> {
                                final var nextState = makeSPDSNode(pred, baseRegister);
                                containingSolver.propagate(context.currentPDSNode(), nextState);
                            });
                }
            }
        } else {
            throw new RuntimeException("Precondition violated: handleUnresolvedCall invoked on node that's not" +
                    "a CallNode: " + node);
        }
    }
}
