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

package com.amazon.pvar.tspoc.merlin.solver.flowfunctions;

import com.amazon.pvar.tspoc.merlin.DebugUtils;
import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.livecollections.LiveCollection;
import com.amazon.pvar.tspoc.merlin.solver.MerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.QueryManager;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.*;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.NodeWithLocation;
import sync.pds.solver.nodes.PopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.interfaces.State;

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
            final var syntheticReadResultRegister = AbstractFlowFunctions.syntheticRegisterForMethodCall(n);
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
        if (Objects.nonNull(n.getTajsFunctionName())) {
            return;
        }

        // propagate the assigned value to the return value of possibly invoked
        // functions, if necessary

        if (context.queryValue().equals(resultReg) ||
                context.queryValue() instanceof ObjectAllocation) {
            final var targetFunctions = resolveFunctionCall(n);
            final var currentSPDSNode = context.currentPDSNode();
            final var queryValue = context.queryValue();
            if (containingSolver != null) {
                final var queryID = containingSolver.getQueryID(currentSPDSNode, false, false);
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
            genSingleNormalFlow(n, read);
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
            final var queryID = containingSolver.getQueryID(context.currentPDSNode(), true, false);
            LiveCollection<CallNode> liveInvokes = findInvocationsOfFunction(containingFunction);
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
                    continueWithSubqueryResult(liveInvokes, queryID, invoke -> {
                        DebugUtils.debug("handleflowToFunctionEntry[param]: found invocation of " + containingFunction +
                                ": " + invoke + " for query: " + queryVal);
                        try {
                            Register reg = new Register(invoke.getArgRegister(paramIndex),
                                    invoke.getBlock().getFunction());
                            State nextState = makeSPDSNode(invoke, reg);
                            DebugUtils.debug("Propagating pop state: " + nextState + " where initialQuery=" +
                                    containingSolver.initialQuery + " and currentSPDSNode: " + currentSPDSNode);
                            containingSolver.propagate(currentSPDSNode, nextState);
                            DebugUtils.debug("Done propagating: " + nextState);
                        } catch (ArrayIndexOutOfBoundsException ignored) {
                        }
                    });
                } else {
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
                continueWithSubqueryResult(liveInvokes, queryID, invoke -> {
                    final var nodeAtSurroundingScope = ((Node) containingFunction.getNode().getBlock().getFunction()
                            .getOrdinaryExit().getFirstNode());
                    final var stateAtSurroundingScope = this.makeSPDSNode(nodeAtSurroundingScope, queryVal);
                    containingSolver.propagate(currentSPDSNode, stateAtSurroundingScope);
                    final var stateAtCallSite = callPopState(invoke, queryVal);
                    containingSolver.propagate(currentSPDSNode, stateAtCallSite);
                });
            } else {
                throw new RuntimeException("BUG: Unexpected query value at function entry: " + queryVal);
            }
        }
    }

}
