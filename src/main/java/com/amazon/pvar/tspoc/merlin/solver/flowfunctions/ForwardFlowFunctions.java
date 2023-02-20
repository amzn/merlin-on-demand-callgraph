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
import com.amazon.pvar.tspoc.merlin.solver.*;
import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.NodeWithLocation;
import sync.pds.solver.nodes.PopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.interfaces.State;

import java.util.*;
import java.util.stream.Collectors;

public class ForwardFlowFunctions extends AbstractFlowFunctions {

    /**
     * Store node successor maps for each function as needed to avoid duplicating
     * computation
     */
    private final Map<Function, Map<AbstractNode, Set<AbstractNode>>> successorMapCache = new HashMap<>();

    public ForwardFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager) {
        super(containingSolver, queryManager);
    }

    @Override
    public Collection<Node> nextNodes(Node n) {
        return getSuccessors(n);
    }

    /**
     * Kill flow of the two arguments at binary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(BinaryOperatorNode n) {
        Set<Value> killed = new HashSet<>();
        final var arg1 = new Register(n.getArg1Register(), n.getBlock().getFunction());
        killed.add(arg1);
        final var arg2 = new Register(n.getArg2Register(), n.getBlock().getFunction());
        killed.add(arg2);
        if (!killed.contains(getQueryValue())) {
            addStandardNormalFlowToNext(n);
        }
    }

    private void addStandardNormalFlowToNext(Node n) {
        getSuccessors(n).forEach(this::addStandardNormalFlow);

    }

    /**
     * Propagates values to the callee (if they are used in the callee),
     * or across the call site (if they are not used in the callee)
     * 
     * @param n
     */
    @Override
    public void visit(CallNode n) {
        // If this is a call to an internal TAJS function, don't try to resolve it
        if (Objects.nonNull(n.getTajsFunctionName()) ||
                n.getSourceLocation().getKind() == SourceLocation.Kind.SYNTHETIC) {
            treatAsNop(n);
            return;
        }

        // Save current flow function state and capture it in closure:
        if (containingSolver != null) {
            final var queryID = containingSolver.getQueryID(currentPDSNode, false, false);
            final var sourceState = currentPDSNode;
            continueWithSubqueryResult(resolveFunctionCall(n), queryID,
                    callee -> this.handleFlowToCallee(n, callee, sourceState));
        }
        // Propagate values across the call site
        treatAsNop(n);
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
     * Add the result register to the map of known registers and propagate dataflow
     *
     * @param n
     */
    @Override
    public void visit(ConstantNode n) {
        final var firstNodeInFunction = n.getBlock().getFunction().getEntry().getFirstNode().equals(n);
        // It might be sufficient to do the below propagation only if the variable name matches a parameter.
        if (firstNodeInFunction && getQueryValue() instanceof Variable queryVar && queryVar.getDeclaringFunction().equals(n.getBlock().getFunction())) {
            // A parameter may be captured by a closure defined here.
            final var capturingFunctions = CapturedVariableAnalysis.functionsCapturingVarIn(n.getBlock().getFunction(), queryVar.getVarName());
            capturingFunctions.forEach(capturingFunc -> handleFlowToClosureVar(queryVar, capturingFunc));
        }
        treatAsNop(n);
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
     * Declared functions are treated the same as any other value in the program,
     * but special care must be given
     * to top-level function declarations that are not assigned to a variable.
     * 
     * @param n
     */
    @Override
    public void visit(DeclareFunctionNode n) {
        if (n.getResultRegister() != -1) {
            // This function declaration is a function expression, assigned to some result
            // register
            // A forward query to find invocations of functions starts from declare function nodes
            // as well, so if we are looking for the function being declared here, we must also
            // add a flow into the result register:
            if (getQueryValue() instanceof FunctionAllocation functionAllocation &&
                    functionAllocation.getAllocationStatement().equals(n)) {
                final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
                genSingleNormalFlow(n, resultReg);
            }
            treatAsNop(n);
        }
        final var functionName = n.getFunction().getName();
        if (functionName != null && !functionName.isBlank()) {
            Variable functionVariable = new Variable(n.getFunction().getName(), n.getBlock().getFunction());
            FunctionAllocation alloc = new FunctionAllocation(n);
            if (getQueryValue().equals(alloc)) {
                genSingleNormalFlow(n, functionVariable);
            }
            treatAsNop(n);
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
     * is handled by the successor relation.
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
     * TODO
     * 
     * @param n
     */
    @Override
    public void visit(NewObjectNode n) {
        treatAsNop(n);
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
     * Propagate all values across nop nodes
     *
     * @param n
     */
    @Override
    public void visit(NopNode n) {
        treatAsNop(n);
    }

    /**
     * Kills flow for the assigned value, adds a property pop rule for the value
     * read from the property, and
     * propagates all other values
     * 
     * @param n
     */
    @Override
    public void visit(ReadPropertyNode n) {
        Register result = new Register(n.getResultRegister(), n.getBlock().getFunction());
        Register baseReg = new Register(n.getBaseRegister(), n.getBlock().getFunction());
        if (n.isPropertyFixed()) {
            // Property is a fixed String
            Property property = new Property(n.getPropertyString());
            if (!getQueryValue().equals(result)) {
                addStandardNormalFlowToNext(n);
            }
            if (n.getResultRegister() == -1) {
                return; // nothing more to do
            }
            final var queryValue = getQueryValue();
            withAllocationSitesOf(n, baseReg, alloc -> {
                assert containingSolver != null;
                DebugUtils.debug("fwd found alias for base of property read " + n + ": " +
                        alloc + "; for query " + queryValue + "; initial query: " + containingSolver.initialQuery);
                if (alloc.equals(queryValue)) {
                    getSuccessors(n)
                            .forEach(succ -> {
                                final var popNode = new PopNode<>(
                                        new NodeWithLocation<>(
                                                makeNodeState(succ),
                                                result,
                                                property
                                        ),
                                        SyncPDSSolver.PDSSystem.FIELDS
                                );
                                final var sourceNode = new sync.pds.solver.nodes.Node<>(makeNodeState(n), (Value)alloc);
                                containingSolver.propagate(sourceNode, popNode);
                            });
                }
            }, queryValue);
        } else {
            // TODO: dispatch a backward query on the register used for the property read
            treatAsNop(n);
        }
    }

    private void handleFlowToCallee(CallNode caller, Function callee, sync.pds.solver.nodes.Node<NodeState, Value> sourceState) {
        assert containingSolver != null;
        final int numArgs = caller.getNumberOfArgs();
        final var queryValue = containingSolver.getFlowFunctions().getQueryValue();
        DebugUtils.debug(this.containingSolver.getQueryString() + "; New target function: " +
                callee + " for query " + this.containingSolver.getQueryString() +
                " and query var " + queryValue + " for call node: " + caller);
        Node entryPoint = ((Node) callee.getEntry().getFirstNode());
        // HACK to ensure we use the original query value
        if ((queryValue instanceof Variable var && var.isVisibleIn(callee)) ||
                (queryValue instanceof ObjectAllocation)) {
            final var nextState = callPushState(entryPoint, queryValue, caller);
            containingSolver.propagate(sourceState, nextState);
        } else {
            for (int i = 0; i < numArgs; i++) {
                Register argRegister = new Register(caller.getArgRegister(i), caller.getBlock().getFunction());
                try {
                    String paramName = callee.getParameterNames().get(i);
                    Variable param = new Variable(paramName, callee);
                    if (queryValue.equals(argRegister)) {
                        DebugUtils.debug("Propagating actual argument " + argRegister
                                + " to function parameter: " + param);
                        final var nextState = callPushState(entryPoint, param, caller);
                        containingSolver.propagate(currentPDSNode, nextState);
                    }
                } catch (IndexOutOfBoundsException e) {
                    // Do nothing, if we pass an extra unused arg to a function, there's no need to
                    // propagate
                }
            }
        }
    }

    /**
     * Propagate data from the read variable to the result register, killing
     * previous flow at the register
     *
     * @param n
     */
    @Override
    public void visit(ReadVariableNode n) {
        Register result = new Register(n.getResultRegister(), n.getBlock().getFunction());
        Variable read = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction()));
        if (!getQueryValue().equals(result)) {
            addStandardNormalFlowToNext(n);
        }
        if (getQueryValue().equals(read)) {
            genSingleNormalFlow(n, result);
        }
    }

    /**
     * Propagate return flow back to possible call sites that call this function.
     *
     * Note that the SPDS framework handles context sensitivity and will not
     * continue propagating data flow along
     * any paths where calls and returns are not properly matched.
     * 
     * @param n
     */
    @Override
    public void visit(ReturnNode n) {
        if (n.getBlock().getFunction().isMain()) {
            // end of program
            return;
        }
        Register result = new Register(n.getReturnValueRegister(), n.getBlock().getFunction());
        // Handle return value assignment
        final var containingFunction = n.getBlock().getFunction();
        LiveCollection<CallNode> possibleReturnSites = findInvocationsOfFunction(containingFunction);
        if (containingSolver != null) {
            final var currentSPDSNode = currentPDSNode;
            final var queryID = containingSolver.getQueryID(currentSPDSNode, true, false);
            final var queryValue = getQueryValue();
            continueWithSubqueryResult(possibleReturnSites, queryID, (returnSite, newFlowFunctions)-> {
                if (queryValue.equals(result)) {
                    DebugUtils.debug("Found return site: " + returnSite + " for " +
                            n.getBlock().getFunction() + "[fwd query: " + containingSolver.initialQuery + "]");
                    Register returnReg = new Register(returnSite.getResultRegister(),
                            returnSite.getBlock().getFunction());
//                    getSuccessors(returnSite)
//                            .forEach(returnSucc -> {
//                                State popState = callPopState(returnSucc, returnReg);
//                                containingSolver.propagate(currentSPDSNode, popState);
//                            });
                    State popState = callPopState(returnSite, returnReg);
                    if (popState.toString().contains("v9")) {
                        final var breakpoin = 1;
                    }
                    containingSolver.propagate(currentSPDSNode, popState);
//                    containingSolver.solve();
                }
                // Handle return propagation of formal parameters
                // TODO: and any other values visible but not declared in this function scope
                for (int i = 0; i < containingFunction.getParameterNames().size(); i++) {
                    final var paramVar = new Variable(containingFunction.getParameterNames().get(i),
                            containingFunction);
                    if (getQueryValue().equals(paramVar)) {
                        // Find corresponding actual parameter at returnSite:
                        // Note that the caller may have passed too few arguments. In that case, we
                        // don't propagate the flow
                        if (i < returnSite.getNumberOfArgs()) {
                            final var actualReg = new Register(returnSite.getArgRegister(i),
                                    returnSite.getBlock().getFunction());
                            getSuccessors(returnSite).forEach(returnSucc -> {
                                final var nextState = makeSPDSNode(returnSucc, actualReg);
                                containingSolver.propagate(currentSPDSNode, nextState);
                            });
                        }
                    }
                }
                if ((queryValue instanceof Variable var && var.isVisibleIn(returnSite.getBlock().getFunction())) ||
                        queryValue instanceof Allocation) {
                    getSuccessors(returnSite)
                            .forEach(returnSucc -> {
                                final var nextState = makeSPDSNode(returnSucc, queryValue);
                                containingSolver.propagate(currentSPDSNode, nextState);
                            });
                }
            });
        }

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
     * Kill flow of the argument at unary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(UnaryOperatorNode n) {
        final var argRegister = new Register(n.getArgRegister(), n.getBlock().getFunction());
        if (!getQueryValue().equals(argRegister)) {
            addStandardNormalFlowToNext(n);
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
     * Adds a property push rule for the value written to the property, and
     * propagates all other values
     *
     * @param n
     */
    @Override
    public void visit(WritePropertyNode n) {
        Value valueReg = new Register(n.getValueRegister(), n.getBlock().getFunction());
        Value baseReg = new Register(n.getBaseRegister(), n.getBlock().getFunction());
        if (n.isPropertyFixed()) {
            // Property is a fixed String
            Property property = new Property(n.getPropertyString());
            treatAsNop(n);
            if (getQueryValue().equals(valueReg)) {
                // Propagate to aliases
                final var currentPDSNode = this.currentPDSNode;
                withAllocationSitesOf(n, baseReg, alloc -> getSuccessors(n)
                            .forEach(succ -> {
                                final var pushNode = new PushNode<>(
                                        makeNodeState(succ),
                                        alloc,
                                        property,
                                        SyncPDSSolver.PDSSystem.FIELDS
                                );
                                assert containingSolver != null;
                                containingSolver.propagate(currentPDSNode, pushNode);
                            }), getQueryValue());
            }
        } else {
            // TODO: dispatch a backward query on the register used for the property read
            treatAsNop(n);
        }
    }

    /**
     * Propagate data from the argument register to the written variable, killing
     * previous flow at the variable
     *
     * @param n
     */
    @Override
    public void visit(WriteVariableNode n) {
        Register argRegister = new Register(n.getValueRegister(), n.getBlock().getFunction());
        Variable write = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction()));

        if (!getQueryValue().equals(write)) {
            addStandardNormalFlowToNext(n);
        }

        if (getQueryValue().equals(argRegister) || // ad-hoc fix:
                getQueryValue() instanceof ObjectAllocation objAlloc && objAlloc.getAllocationStatement().getResultRegister() == n.getValueRegister()
        ) {
            genSingleNormalFlow(n, write);

            // If the variable we are writing to flows to a closure, we also need to propagate flow there. See
            // closure-handling.md for details.
            final var capturingFunctions = CapturedVariableAnalysis.functionsCapturingVarIn(n.getBlock().getFunction(), n.getVariableName());
            final var capturedVar = new Variable(n.getVariableName(), n.getBlock().getFunction());
            capturingFunctions.forEach(capturingFunc -> handleFlowToClosureVar(capturedVar, capturingFunc));
        }

    }

    /**
     * Merlin does not handle eventdispatchernodes, but does flag this unsoundness
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
     * No need to do anything special to handle begin loop nodes for forward
     * analysis.
     * 
     * @param n
     */
    @Override
    public void visit(BeginLoopNode n) {
        treatAsNop(n);
    }

    /**
     * No need to do anything special to handle end loop nodes in the forward
     * analysis.
     * 
     * @param n
     */
    @Override
    public void visit(EndLoopNode n) {
        treatAsNop(n);
    }

//    @Override
//    protected synchronized void killAt(Node n, Set<Value> killed) {
//        throw new RuntimeException("to be removed");
//        getSuccessors(n)
//                .forEach(node -> addStandardNormalFlow(node, killed));
//    }

    @Override
    protected synchronized void treatAsNop(Node n) {
        getSuccessors(n)
                .forEach(this::addStandardNormalFlow);
    }

    @Override
    protected synchronized void genSingleNormalFlow(Node n, Value v) {
        getSuccessors(n)
                .forEach(node -> addSingleState(node, v));
    }

    public static Map<AbstractNode, Set<AbstractNode>> invertMapping(Map<AbstractNode, Set<AbstractNode>> multimap) {
        Map<AbstractNode, Set<AbstractNode>> inverse = new HashMap<>();
        multimap.forEach((key, set) -> {
            set.forEach(value -> {
                if (inverse.containsKey(value)) {
                    inverse.get(value).add(key);
                } else {
                    Set<AbstractNode> inverseSet = new HashSet<>();
                    inverseSet.add(key);
                    inverse.put(value, inverseSet);
                }
            });
        });
        return inverse;
    }

    private synchronized Collection<Node> getSuccessors(Node n) {
        return successorMapCache
                .computeIfAbsent(
                        n.getBlock().getFunction(),
                        f -> invertMapping(FlowGraphBuilder.makeNodePredecessorMap(f)))
                .getOrDefault(n, Collections.emptySet())
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode))
                .collect(Collectors.toSet());
    }

    private ForwardFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager,
            FlowFunctionState savedState) {
        super(containingSolver, queryManager);
        this.queryValue = savedState.queryValue();
        this.currentPDSNode = savedState.pdsNode();

    }

    @Override
    protected ForwardFlowFunctions copyFromFlowFunctionState(FlowFunctionState state) {
        return new ForwardFlowFunctions(containingSolver, queryManager, state);
    }

    private void handleFlowToClosureVar(Variable capturedVar, DeclareFunctionNode capturingFunction) {
        if (containingSolver != null) {
            final var callSitesAndQuery = findInvocationsOfFunctionWithQuery(capturingFunction.getFunction());
            final var queryID = new CapturedVariableQuery(new Query(currentPDSNode, true), callSitesAndQuery.getSecond());
            continueWithSubqueryResult(callSitesAndQuery.getFirst(), queryID, callSite -> {
                final var callSiteState = makeSPDSNode(callSite, capturedVar);
                final var initialState = containingSolver.initialQuery;
                // flow from initial state to call site
                containingSolver.propagate(initialState, callSiteState);
                // flow from call site into function:
                final var inFuncState = makeSPDSNode(
                        (Node) capturingFunction.getFunction().getEntry().getFirstNode(),
                        capturedVar
                );
                containingSolver.propagate(callSiteState, inFuncState);
            });
        }
    }
}
