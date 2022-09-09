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

import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.amazon.pvar.tspoc.merlin.solver.ForwardMerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.MerlinSolverFactory;
import com.amazon.pvar.tspoc.merlin.solver.querygraph.QueryGraph;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import dk.brics.tajs.flowgraph.jsnodes.NodeVisitor;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.CallPopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.interfaces.State;

import java.util.*;

/**
 * This abstract class collects behaviour that is common to both forward and backward flow functions within Merlin's
 * framework.
 *
 * This class's sole public method can be invoked to apply a flow function at a particular node and obtain the next
 * SPDS states after the flow function is applied.
 */
public abstract class AbstractFlowFunctions implements NodeVisitor {

    protected final Map<Node, NodeState> wrappedNodeMap = new HashMap<>();
    protected final Set<Variable> declaredVariables = new HashSet<>();
    protected final Map<Integer, Register> usedRegisters = new HashMap<>();
    /**
     * The call graph is solely used for looking up possible successors to function return statements
     */
    protected final CallGraph callGraph;
    private Set<State> nextStates;
    private Value queryValue;

    public AbstractFlowFunctions(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    /**
     * Create an SPDS-compatible Node<> from a NodeState and a value
     * @param nodeState
     * @param value
     * @return
     */
    protected sync.pds.solver.nodes.Node<NodeState, Value> makeSPDSNode(NodeState nodeState, Value value) {
        return new sync.pds.solver.nodes.Node<>(nodeState, value);
    }

    /**
     * Create an SPDS-compatible Node<> from a TAJS flowgraph node and a value
     * @param jsNode
     * @param value
     * @return
     */
    protected sync.pds.solver.nodes.Node<NodeState, Value> makeSPDSNode(Node jsNode, Value value) {
        NodeState nodeState = makeNodeState(jsNode);
        return makeSPDSNode(
                nodeState,
                value
        );
    }

    /**
     * Creates a new NodeState from a given node if it is not already present in the wrappedNodeMap
     * @param n
     * @return
     */
    protected NodeState makeNodeState(Node n) {
        NodeState nodeState;
        if (wrappedNodeMap.containsKey(n)) {
            nodeState = wrappedNodeMap.get(n);
        } else {
            nodeState = new NodeState(n);
            wrappedNodeMap.put(n, nodeState);
        }
        return nodeState;
    }

    /**
     * Apply flow function at the provided node and obtain the next states. Next states are stored in the nextStates
     * member of this class.
     *
     * Classes that extend this abstract class should implement "visit" methods that update (but do not re-assign)
     * the nextStates attribute.
     *
     * @param node
     * @return
     */
    public Set<State> computeNextStates(Node node, Value val) {
        queryValue = val;
        nextStates = new HashSet<>();
        if (val instanceof Register register) {
            usedRegisters.putIfAbsent(register.getId(), register);
        } else if (val instanceof Variable variable) {
            declaredVariables.add(variable);
        }
        node.visitBy(this);
        return nextStates;
    }

    protected void addStandardNormalFlow(Node next, Set<Value> killedValues) {
        declaredVariables.forEach(var -> {
            if (!killedValues.contains(var) && var.equals(queryValue)) {
                nextStates.add(makeSPDSNode(next, var));
            }
        });

        usedRegisters.values().forEach(register -> {
            if (!killedValues.contains(register) && register.equals(queryValue)) {
                nextStates.add(makeSPDSNode(next, register));
            }
        });
    }

    protected void addStandardNormalFlow(Node next) {
        declaredVariables.forEach(var -> {
            if (var.equals(queryValue)) {
                nextStates.add(makeSPDSNode(next, var));
            }
        });

        usedRegisters.values().forEach(register -> {
            if (register.equals(queryValue)) {
                nextStates.add(makeSPDSNode(next, register));
            }
        });
    }

    protected void addSingleState(Node n, Value v) {
        nextStates.add(makeSPDSNode(n, v));
    }

    protected void addSinglePushState(Node n, Value v, Node location, SyncPDSSolver.PDSSystem system) {
        nextStates.add(
                new PushNode<>(
                        makeNodeState(n),
                        v,
                        makeNodeState(location),
                        system
                )
        );
    }

    protected void addSinglePopState(Node n, Value v, SyncPDSSolver.PDSSystem system) {
        nextStates.add(
                new CallPopNode<>(
                        v,
                        system,
                        makeNodeState(n)
                )
        );
    }

    protected Value getQueryValue() {
        return queryValue;
    }

    protected void logUnsoundness(Node node) {
        System.err.println("Warning - Unsoundness from unhandled language feature at:\n" +
                "\t" + node.getSourceLocation().toUserFriendlyString(true) + "\n");
    }

    /**
     * Given a variable name and the function in which it is used, statically determine the function that declares the
     * variable
     *
     * @param varName
     * @param usageScope
     * @return
     */
    protected Function getDeclaringScope(String varName, Function usageScope) {
        Function currentScope = usageScope;
        if (varName.equals("process")) {
            // Accessing arguments, process is always declared by the runtime in the outermost (main) scope
            while (!currentScope.isMain()) {
                currentScope = currentScope.getOuterFunction();
            }
            return currentScope;
        }
        while (Objects.nonNull(currentScope)) {
            if (
                    currentScope.getVariableNames().contains(varName) ||
                    currentScope.getParameterNames().contains(varName) ||
                    scopeDeclaresFunctionWithName(currentScope, varName)
            ) {
                return currentScope;
            }
            currentScope = currentScope.getOuterFunction();
        }
        throw new RuntimeException("Cannot get declaring scope of variable '" + varName + "' in function '" +
                usageScope + "': '" + varName + "' should not be visible in this scope.");
    }

    /**
     * @param currentScope
     * @param functionName
     * @return true if currentScope declares a function with name functionName, false otherwise
     */
    private static boolean scopeDeclaresFunctionWithName(Function currentScope, String functionName) {
        return currentScope.getBlocks().stream()
                .flatMap(block -> block.getNodes().stream())
                .anyMatch(node -> {
                    try {
                        return node instanceof DeclareFunctionNode &&
                                ((DeclareFunctionNode) node).getFunction().getName().equals(functionName);
                    } catch (NullPointerException e) {
                        return false;
                    }
                });
    }

    /**
     * Propagate dataflow for all known values across Node n
     *
     * @param n
     */
    protected abstract void treatAsNop(Node n);

    protected abstract void genSingleNormalFlow(Node n, Value v);

    protected abstract void killAt(Node n, Set<Value> killed);

    /**
     * Find call sites where the provided function may be called by issuing a new forward query on the function
     *
     * @param function
     * @return
     */
    protected Collection<CallNode> findInvocationsOfFunction(Function function) {
        // If invocation information for this function is already cached, consult the call graph instead of issuing
        // a new query
        if (FlowFunctionUtil.allInvocationsFound.contains(function)) {
            return callGraph.getCallers(function);
        }
        DeclareFunctionNode functionDeclaration = function.getNode();
        FunctionAllocation alloc = new FunctionAllocation(functionDeclaration);
        sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                new NodeState(functionDeclaration),
                alloc
        );
        ForwardMerlinSolver solver = MerlinSolverFactory.getNewForwardSolver(initialQuery);
        QueryGraph.getInstance().addEdge(MerlinSolverFactory.peekCurrentActiveSolver(), solver);
        MerlinSolverFactory.addNewActiveSolver(solver);
        solver.solve();
        MerlinSolverFactory.removeCurrentActiveSolver();
        FlowFunctionUtil.allInvocationsFound.add(function);
        return solver.getPointsToGraph().getKnownFunctionInvocations(alloc);
    }

    protected static class FlowFunctionUtil {
        protected static final Set<Function> allInvocationsFound = new HashSet<>();
        protected static final Set<CallNode> allCalleesFound = new HashSet<>();
    }
}
