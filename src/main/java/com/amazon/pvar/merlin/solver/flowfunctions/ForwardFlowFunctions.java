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
import com.amazon.pvar.merlin.ir.Allocation;
import com.amazon.pvar.merlin.ir.FlowgraphUtils;
import com.amazon.pvar.merlin.ir.FunctionAllocation;
import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.ObjectAllocation;
import com.amazon.pvar.merlin.ir.Property;
import com.amazon.pvar.merlin.ir.Register;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.ir.Variable;
import com.amazon.pvar.merlin.solver.CapturedVariableAnalysis;
import com.amazon.pvar.merlin.solver.CapturedVariableQuery;
import com.amazon.pvar.merlin.solver.MerlinSolver;
import com.amazon.pvar.merlin.solver.Query;
import com.amazon.pvar.merlin.solver.QueryID;
import com.amazon.pvar.merlin.solver.QueryManager;
import com.amazon.pvar.merlin.ir.*;
import com.amazon.pvar.merlin.livecollections.LiveCollection;
import com.amazon.pvar.merlin.solver.*;
import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.*;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.NodeWithLocation;
import sync.pds.solver.nodes.PopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.interfaces.State;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ForwardFlowFunctions extends AbstractFlowFunctions {


    public ForwardFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager, FlowFunctionContext context) {
        super(containingSolver, queryManager, context);
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
        if (!killed.contains(context.queryValue())) {
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
            final var queryID = containingSolver.getQueryID(context.currentPDSNode(), false, false);
            final var sourceState = context.currentPDSNode();

            /* Should only analyze callee if
            1. looking for an object allocation
            2. query value passed as a parameter.
            3. query value is a variable (which may have been captured in a callee)
             */
            var shouldAnalyzeCallee = (context.queryValue() instanceof ObjectAllocation);

            // case (2)
            final var callParamRegs = IntStream.range(0, n.getNumberOfArgs())
                    .mapToObj(argIdx -> new Register(n.getArgRegister(argIdx), n.getBlock().getFunction()))
                    .collect(Collectors.toSet());
            shouldAnalyzeCallee = shouldAnalyzeCallee || callParamRegs.contains(context.queryValue());

            shouldAnalyzeCallee = shouldAnalyzeCallee || (context.queryValue() instanceof Variable);


            if (shouldAnalyzeCallee) {
                final var functionCallTargetsAndQueries = resolveFunctionCallWithQueries(n, queryManager);
                final var functionCallTargets = functionCallTargetsAndQueries.getFirst();
                final var functionCallQueries = functionCallTargetsAndQueries.getSecond();
                functionCallQueries
                        .forEach(subquery ->
                                queryManager.registerQueryDependency(
                                        containingSolver.initialQueryWithDirection(),
                                        subquery
                                ));
                registerCalleeHandler(n, queryID, sourceState, functionCallTargets);
            }
        }
        // Propagate values across the call site
        treatAsNop(n);
    }

    private void registerCalleeHandler(CallNode n, QueryID queryID, sync.pds.solver.nodes.Node<NodeState, Value> sourceState, LiveCollection<Function> functionCallTargets) {
        continueWithSubqueryResult(functionCallTargets, queryID,
                callee -> {
                    if (!(context.queryValue() instanceof Variable var) || var.isVisibleIn(callee)) {
                        this.handleFlowToCallee(n, callee, sourceState, context);
                    }
                });
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
        // It might be sufficient to do the below propagation only if the variable name
        // matches a parameter.
        if (firstNodeInFunction && context.queryValue() instanceof Variable queryVar
                && queryVar.getDeclaringFunction().equals(n.getBlock().getFunction())) {
            // A parameter may be captured by a closure defined here.
            final var capturingFunctions = CapturedVariableAnalysis.functionsCapturingVarIn(n.getBlock().getFunction(),
                    queryVar.getVarName());
            capturingFunctions.forEach(capturingFunc -> handleFlowToClosureVar(queryVar, capturingFunc, context));
        }
        if (context.queryValue().equals(new ConstantAllocation(n))) {
            genSingleNormalFlow(n, new Register(n.getResultRegister(), n.getBlock().getFunction()));
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
            // A forward query to find invocations of functions starts from declare function
            // nodes
            // as well, so if we are looking for the function being declared here, we must
            // also
            // add a flow into the result register:
            final var resultReg = new Register(n.getResultRegister(), n.getBlock().getFunction());
            if (context.queryValue() instanceof FunctionAllocation functionAllocation &&
                    functionAllocation.getAllocationStatement().equals(n)) {
                genSingleNormalFlow(n, resultReg);
            } else if (!context.queryValue().equals(resultReg)) {
                treatAsNop(n);
            }
        }
        final var functionName = n.getFunction().getName();
        if (functionName != null && !functionName.isBlank()) {
            Variable functionVariable = new Variable(n.getFunction().getName(), n.getBlock().getFunction());
            FunctionAllocation alloc = new FunctionAllocation(n);
            if (context.queryValue().equals(alloc)) {
//                genSingleNormalFlow(n, functionVariable);
                final var syntacticReferences = FlowgraphUtils.findReferencesToFunctionName(n.getFunction());
                syntacticReferences.forEach(reference -> {
//                    this.queryManager.getOrStartForwardQuery(new sync.pds.solver.nodes.Node<>(new NodeState(reference), functionVariable));
                    final var succs = FlowgraphUtils.successorsOf(reference);
                    succs.forEach(succ -> {
                        addSingleState(succ, new Register(((ReadVariableNode)reference).getResultRegister(), reference.getBlock().getFunction()));
                    });
                });
            } else if (!context.queryValue().equals(functionVariable)) {
                treatAsNop(n);
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
            if (!context.queryValue().equals(result)) {
                addStandardNormalFlowToNext(n);
            }
            if (n.getResultRegister() == -1) {
                return; // nothing more to do
            }
            final var queryValue = context.queryValue();
            if (queryValue instanceof ObjectAllocation) {
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
                                                    property),
                                            SyncPDSSolver.PDSSystem.FIELDS);
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

    private void handleFlowToCallee(
            CallNode caller,
            Function callee,
            sync.pds.solver.nodes.Node<NodeState, Value> sourceState,
            FlowFunctionContext context) {
        assert containingSolver != null;
        final int numArgs = caller.getNumberOfArgs();
        final var queryValue = context.queryValue();
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
                        containingSolver.propagate(context.currentPDSNode(), nextState);
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
        if (!context.queryValue().equals(result)) {
            addStandardNormalFlowToNext(n);
        }
        if (context.queryValue().equals(read)) {
            genSingleNormalFlow(n, result);
        }
    }

    private void handleFlowToReturn(ReturnNode n) {
        if (n.getBlock().getFunction().isMain()) {
            // end of program
            return;
        }
        final var containingFunction = n.getBlock().getFunction();
        Register result = new Register(n.getReturnValueRegister(), n.getBlock().getFunction());
        if (context.queryValue() instanceof Register reg && reg.getContainingFunction().equals(containingFunction) &&
        !reg.equals(result)) {
            // If the register goes out of scope here, kill the flow
            return;
        }
        // Handle return value assignment
        if (containingSolver != null) {
            LiveCollection<CallNode> possibleReturnSites = findInvocationsOfFunction(containingFunction);
            final var currentSPDSNode = context.currentPDSNode();
            final var queryID = containingSolver.getQueryID(currentSPDSNode, true, false);
            final var queryValue = context.queryValue();
            continueWithSubqueryResult(possibleReturnSites, queryID, returnSite -> {
                if (queryValue.equals(result)) {
                    DebugUtils.debug("Found return site: " + returnSite + " for " +
                            n.getBlock().getFunction() + "[fwd query: " + containingSolver.initialQuery + "]");
                    Register returnReg = new Register(returnSite.getResultRegister(),
                            returnSite.getBlock().getFunction());
                    State popState = callPopState(returnSite, returnReg);
                    containingSolver.propagate(currentSPDSNode, popState);
                }
                // Handle return propagation of formal parameters
                // TODO: and any other values visible but not declared in this function scope
                for (int i = 0; i < containingFunction.getParameterNames().size(); i++) {
                    final var paramVar = new Variable(containingFunction.getParameterNames().get(i),
                            containingFunction);
                    if (context.queryValue().equals(paramVar)) {
                        // Find corresponding actual parameter at returnSite:
                        // Note that the caller may have passed too few arguments. In that case, we
                        // don't propagate the flow
                        if (i < returnSite.getNumberOfArgs()) {
                            final var actualReg = new Register(returnSite.getArgRegister(i),
                                    returnSite.getBlock().getFunction());
                            getSuccessors(returnSite).forEach(returnSucc -> {
                                final var nextState = makeSPDSNode(returnSucc, actualReg);
//                                final var nextState = callPopState(returnSucc, actualReg);
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
        handleFlowToReturn(n);
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
        if (!context.queryValue().equals(argRegister)) {
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
            if (context.queryValue().equals(valueReg)) {
                // Propagate to aliases
                final var currentPDSNode = context.currentPDSNode();
                withAllocationSitesOf(n, baseReg, alloc -> getSuccessors(n)
                        .forEach(succ -> {
                            final var pushNode = new PushNode<>(
                                    makeNodeState(succ),
                                    alloc,
                                    property,
                                    SyncPDSSolver.PDSSystem.FIELDS);
                            assert containingSolver != null;
                            containingSolver.propagate(currentPDSNode, pushNode);
                        }), context.queryValue());
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

        if (!context.queryValue().equals(write)) {
            addStandardNormalFlowToNext(n);
        }
        if (context.queryValue().equals(argRegister) || // ad-hoc fix:
                context.queryValue() instanceof ObjectAllocation objAlloc
                        && objAlloc.getResultRegister().getId() == n.getValueRegister()) {
            genSingleNormalFlow(n, write);

            // If the variable we are writing to flows to a closure, we also need to
            // propagate flow there. See
            // closure-handling.md for details.
            final var capturingFunctions = CapturedVariableAnalysis.functionsCapturingVarIn(n.getBlock().getFunction(),
                    n.getVariableName());
            final var capturedVar = new Variable(n.getVariableName(), n.getBlock().getFunction());
            capturingFunctions.forEach(capturingFunc -> handleFlowToClosureVar(capturedVar, capturingFunc, context));
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

    protected void treatAsNop(Node n) {
        getSuccessors(n)
                .forEach(this::addStandardNormalFlow);
    }

    @Override
    protected void genSingleNormalFlow(Node n, Value v) {
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

    private Collection<Node> getSuccessors(Node n) {
        return FlowgraphUtils.successorsOf(n).collect(Collectors.toSet());
    }

    private void handleFlowToClosureVar(Variable capturedVar, DeclareFunctionNode capturingFunction,
            FlowFunctionContext context) {
        if (containingSolver != null) {
            final var callSitesAndQuery = findInvocationsOfFunctionWithQuery(capturingFunction.getFunction());
            final var queryID = new CapturedVariableQuery(new Query(context.currentPDSNode(), true),
                    callSitesAndQuery.getSecond());
            queryManager.registerQueryDependency(
                    containingSolver.initialQueryWithDirection(),
                    callSitesAndQuery.getSecond()
            );
            continueWithSubqueryResult(callSitesAndQuery.getFirst(), queryID, callSite -> {
                final var callSiteState = makeSPDSNode(callSite, capturedVar);
                final var initialState = containingSolver.initialQuery;
                // flow from initial state to call site
                containingSolver.propagate(initialState, callSiteState);
                // flow from call site into function:
                final var inFuncState = makeSPDSNode(
                        (Node) capturingFunction.getFunction().getEntry().getFirstNode(),
                        capturedVar);
                containingSolver.propagate(callSiteState, inFuncState);
            });
        }
    }

    @Override
    public void handleUnresolvedCall() {
        assert containingSolver != null;
        final var node = context.currentPDSNode().stmt().getNode();
        if (node instanceof CallNode callNode && callNode.getResultRegister() != 1) {
            final var parameters = IntStream.range(0, callNode.getNumberOfArgs())
                    .mapToObj(argIdx -> new Register(callNode.getArgRegister(argIdx), callNode.getBlock().getFunction()))
                    .collect(Collectors.toSet());
            if (parameters.contains(context.queryValue())) {
                // add flow into result
                final var resultReg = new Register(callNode.getResultRegister(), callNode.getBlock().getFunction());
                getSuccessors(callNode)
                        .forEach(succ -> containingSolver.propagate(context.currentPDSNode(), makeSPDSNode(succ, resultReg)));
            }
        } else {
            logUnsoundness(node, "Precondition violated: handleUnresolvedCall transfer invoked for non-call-node: " + node);
        }
    }
}
