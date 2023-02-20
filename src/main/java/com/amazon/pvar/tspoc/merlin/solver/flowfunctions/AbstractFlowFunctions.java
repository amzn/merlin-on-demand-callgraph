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
import com.amazon.pvar.tspoc.merlin.livecollections.LiveSet;
import com.amazon.pvar.tspoc.merlin.livecollections.TaggedHandler;
import com.amazon.pvar.tspoc.merlin.solver.*;
import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;
import dk.brics.tajs.util.Pair;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.CallPopNode;
import sync.pds.solver.nodes.NodeWithLocation;
import sync.pds.solver.nodes.PopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.interfaces.State;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This abstract class collects behaviour that is common to both forward and backward flow functions within Merlin's
 * framework.
 * <p>
 * This class's sole public method can be invoked to apply a flow function at a particular node and obtain the next
 * SPDS states after the flow function is applied.
 */
public abstract class AbstractFlowFunctions implements NodeVisitor {

    /**
     * Store node predecessor maps for each function as needed to avoid duplicating computation
     */
    private final Map<Function, Map<AbstractNode, Set<AbstractNode>>> predecessorMapCache = Collections.synchronizedMap(new HashMap<>());

    protected sync.pds.solver.nodes.Node<NodeState, Value> currentPDSNode;

    protected final QueryManager queryManager;
    protected Set<State> nextStates;
    protected Value queryValue;

    // TODO: This is only ever null in flow function unit tests. This could be avoided by using
    // a mock object in the flow function tests.
    @Nullable
    protected final MerlinSolver containingSolver;


    public AbstractFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager) {
        this.containingSolver = containingSolver;
        this.queryManager = queryManager;
    }

    /**
     * Given a set of allocations, return the corresponding functions for function allocations.
     */
    public static LiveCollection<Function> allocationsToFunctions(LiveCollection<Allocation> allocations) {
        return allocations.filter(alloc -> alloc instanceof FunctionAllocation)
                .map(alloc -> ((DeclareFunctionNode) alloc.getAllocationStatement()).getFunction());
    }

    private synchronized void addNextState(State nextState) {
        nextStates.add(nextState);
    }

    public abstract Collection<Node> nextNodes(Node n);

    /**
     * Create an SPDS-compatible Node<> from a NodeState and a value
     *
     * @param nodeState
     * @param value
     * @return
     */
    protected sync.pds.solver.nodes.Node<NodeState, Value> makeSPDSNode(NodeState nodeState, Value value) {
        return new sync.pds.solver.nodes.Node<>(nodeState, value);
    }

    /**
     * Create an SPDS-compatible Node<> from a TAJS flowgraph node and a value
     *
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
     * Given a function and index, returns the node located at that index
     *
     * @param index
     * @param function
     * @return
     */
    protected Node getNodeByIndex(int index, Function function) {
        return function.getBlocks().stream()
                .flatMap(basicBlock -> basicBlock.getNodes().stream())
                .filter(n -> n instanceof Node)
                .map(n -> ((Node) n))
                .filter(n -> n.getIndex() == index)
                .findAny()
                .orElseThrow();
    }

    protected static NodeState makeNodeState(Node n) {
        return new NodeState(n);
    }

    /**
     * Apply flow function at the provided node and obtain the next states. Next states are stored in the nextStates
     * member of this class.
     * <p>
     * Classes that extend this abstract class should implement "visit" methods that update (but do not re-assign)
     * the nextStates attribute.
     *
     * @param node
     * @return
     */
    public final synchronized Set<State> computeNextStates(Node node, Value val) {
        queryValue = val;
        nextStates = new HashSet<>();
        final var directionLabel = (this instanceof BackwardFlowFunctions) ? "bwd" : "fwd";
        final var lineNum = (node.getSourceLocation() != null)
                ? "L" + node.getSourceLocation().getLineNumber()
                : "L?";
        DebugUtils.debug(directionLabel + "+" + val + ": traversing node: " + node + "[" + node.getIndex() +
                "@" + node.getBlock().getFunction() + "]   " + lineNum);
        final var pdsNode = new sync.pds.solver.nodes.Node<>(new NodeState(node), val);
        currentPDSNode = pdsNode;
        node.visitBy(this);
        return nextStates;
    }

    protected synchronized void addStandardNormalFlow(Node next, Set<Value> killedValues) {
        addNextState(makeSPDSNode(next, queryValue));
    }

    protected synchronized void addStandardNormalFlow(Node next) {
        addNextState(makeSPDSNode(next, queryValue));
    }

    protected synchronized void addSingleState(Node n, Value v) {
        addNextState(makeSPDSNode(n, v));
    }

    protected static State callPushState(Node n, Value v, Node location) {
        return new PushNode<>(
                makeNodeState(n),
                v,
                makeNodeState(location),
                SyncPDSSolver.PDSSystem.CALLS
        );
    }

    protected static State callPopState(Node n, Value v) {
        return new CallPopNode<>(
                v,
                SyncPDSSolver.PDSSystem.CALLS,
                makeNodeState(n)
        );
    }

    protected synchronized void addSinglePropPushState(Node n, Value v, Property location) {
        addNextState(
                new PushNode<>(
                        makeNodeState(n),
                        v,
                        location,
                        SyncPDSSolver.PDSSystem.FIELDS
                )
        );
    }

    protected void addSinglePropPopState(Node n, Value v, Property p) {
        addNextState(
                new PopNode<>(
                        new NodeWithLocation<>(
                                makeNodeState(n),
                                v,
                                p
                        ),
                        SyncPDSSolver.PDSSystem.FIELDS
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
    protected synchronized Function getDeclaringScope(String varName, Function usageScope) {
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
        final var errMsg = "Cannot get declaring scope of variable '" + varName + "' in function '" +
                usageScope + "': '" + varName + "' should not be visible in this scope.";
        System.err.println(errMsg + "\nFalling back to containing function");
        // throw new RuntimeException(errMsg);
        return usageScope;
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


    /**
     * Find call sites where the provided function may be called by issuing a new forward query on the function
     */
    public synchronized LiveCollection<CallNode> findInvocationsOfFunction(Function function) {
        return findInvocationsOfFunctionWithQuery(function).getFirst();
    }

    public synchronized Pair<LiveCollection<CallNode>, Query> findInvocationsOfFunctionWithQuery(Function function) {
        DeclareFunctionNode functionDeclaration = function.getNode();
        FunctionAllocation alloc = new FunctionAllocation(functionDeclaration);
        sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                new NodeState(functionDeclaration),
                alloc
        );
//        final Set<Query> queries = new HashSet<>();
        final var solver = queryManager.getOrStartForwardQuery(initialQuery);
        final var query = new Query(initialQuery, true);
        var result = solver.getPointsToGraph().getKnownFunctionInvocations(alloc);
//        queries.add(query);
//        // We need to search by name and by register if present
//        if (functionDeclaration.getResultRegister() != -1) {
//            final var initialRegQuery = new sync.pds.solver.nodes.Node<>(
//                    new NodeState(functionDeclaration),
//                    new Register(functionDeclaration.getResultRegister(), functionDeclaration.getBlock().getFunction())
//            );
//            final var regSolver = queryManager.getOrStartForwardQuery(initialRegQuery);
//            queries.
//        }
        return Pair.make(result, query);
    }

    /**
     * Retrieves the variable associated with the base register for a ReadPropertyNode. The variable is always loaded
     * into the base register in a ReadVariableNode that occurs one node before the ReadPropertyNode.
     *
     * @param n
     * @return
     */
    protected synchronized Variable getBaseForReadPropertyNode(ReadPropertyNode n) {
        ReadVariableNode baseRead = ((ReadVariableNode) getNodeByIndex(
                n.getIndex() - 1,
                n.getBlock().getFunction()
        ));
        return new Variable(baseRead.getVariableName(), n.getBlock().getFunction());
    }

    /**
     * Retrieves the variable associated with the result register for a ReadPropertyNode. The variable is always written
     * to from the result register in a WriteVariableNode that occurs one node after the ReadPropertyNode.
     *
     * @param n
     * @return
     */
    protected synchronized Variable getResultForReadPropertyNode(ReadPropertyNode n) {
        WriteVariableNode resultWrite = ((WriteVariableNode) getNodeByIndex(
                n.getIndex() + 1,
                n.getBlock().getFunction()
        ));
        return new Variable(resultWrite.getVariableName(), n.getBlock().getFunction());
    }

    /**
     * Retrieves the variable associated with the base register for a WritePropertyNode. The variable is always loaded
     * into the base register in a ReadVariableNode that occurs two nodes before the WritePropertyNode.
     *
     * @param n
     * @return
     */
    protected synchronized Variable getBaseForWritePropertyNode(WritePropertyNode n) {
        ReadVariableNode baseRead = ((ReadVariableNode) getNodeByIndex(
                n.getIndex() - 2,
                n.getBlock().getFunction()
        ));
        return new Variable(baseRead.getVariableName(), n.getBlock().getFunction());
    }

    /**
     * Retrieves the variable associated with the value register for a WritePropertyNode. The variable is always loaded
     * into the value register in a ReadVariableNode that occurs one node before the WritePropertyNode.
     *
     * @param n
     * @return
     */
    protected synchronized Value getValForWritePropertyNode(WritePropertyNode n) {
        Node node = getNodeByIndex(
                n.getIndex() - 1,
                n.getBlock().getFunction()
        );
        if (node instanceof ReadVariableNode baseRead) {
            return new Variable(baseRead.getVariableName(), n.getBlock().getFunction());
        } else if (node instanceof ConstantNode constantNode) {
            return new ConstantAllocation(constantNode);
        } else if (node instanceof NewObjectNode newObjectNode) {
            return new ObjectAllocation(newObjectNode);
        } else if (node instanceof DeclareFunctionNode declareFunctionNode) {
            return new FunctionAllocation(declareFunctionNode);
        }
        throw new RuntimeException("Unknown type for WritePropertyNode predecessor: " + node.getClass());
    }

    /**
     * Find all functions that could be call targets of the provided CallNode
     *
     * @param n
     * @return
     */
    protected synchronized LiveCollection<Function> resolveFunctionCall(CallNode n) {
        LiveCollection<Allocation> predecessorUnion = new LiveSet<>(queryManager.scheduler());
        final var funcReg = new Register(n.getFunctionRegister(), n.getBlock().getFunction());
        for (var predecessor : getPredecessors(n)) {
            sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                    new NodeState(predecessor),
                    funcReg
            );
            final var solver = queryManager.getOrStartBackwardQuery(initialQuery);
            predecessorUnion = solver.getPointsToGraph().getPointsToSet(predecessor, funcReg)
                    .union(predecessorUnion);
        }
        return AbstractFlowFunctions.allocationsToFunctions(predecessorUnion);
    }

    protected synchronized Collection<Node> getPredecessors(Node n) {
        return predecessorMapCache
                .computeIfAbsent(n.getBlock().getFunction(), FlowGraphBuilder::makeNodePredecessorMap)
                .get(n)
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode))
                .collect(Collectors.toSet());
    }

    /**
     * A record for containing all mutable state of the current flow functions.
     * This allows saving the current flow function state and restoring it when new results
     * of subqueries are discovered. See `visit(CallNode n)` in BackwardFlowFunctions for
     * an example.
     */
    protected record FlowFunctionState(
            Value queryValue,
            sync.pds.solver.nodes.Node<NodeState, Value> pdsNode) {
    }

    /**
     * Create a new instance of the flow functions initialized to the given state. The state
     * of the resulting flow functions will be a deep copy of the given state.
     */
    protected abstract AbstractFlowFunctions copyFromFlowFunctionState(FlowFunctionState state);

    protected FlowFunctionState copyFlowFunctionState() {
        final var queryValue = getQueryValue();
        final var currentSPDSNode = currentPDSNode;
        return new FlowFunctionState(queryValue, currentSPDSNode);
    }

    /**
     * Executes the given `handler` on each result discovered by `subquery`. The handler is executed
     * with `containingSolver`s flow function instance set to a new flow function instance with the state at
     * the point where `continueWithSubqueryResult` was invoked.
     * <p/>
     * The handler code should avoid modifying any mutable state on the invoking flow function instance.
     * If the state of the new flow function instance needs to be modified directly (rather than through
     * calls to the `containingSolver`, use the second overloaded variant instead).
     * <p/>
     * To ensure predictable results, the handler code must not modify the mutable state of the invoking
     * instance.
     */
    public final <A> void continueWithSubqueryResult(LiveCollection<A> subquery, QueryID queryID, Consumer<A> handler) {
        continueWithSubqueryResult(subquery, queryID, (result, newFlowFunctions) -> handler.accept(result));
    }

    public final <A> void continueWithSubqueryResult(LiveCollection<A> subquery, QueryID queryID, BiConsumer<A, AbstractFlowFunctions> handler) {
        final var flowFunctionState = copyFlowFunctionState();
        if (containingSolver != null) {
            subquery.onAdd(TaggedHandler.create(queryID, result -> {
                final var newFlowFunctions = copyFromFlowFunctionState(flowFunctionState);
                containingSolver.withFlowFunctions(newFlowFunctions, () -> handler.accept(result, newFlowFunctions));
            }));
        }
    }

    public final void withAllocationSitesOf(Node location, Value value, Consumer<Allocation> handler, Value originatingQueryValue) {
        if (containingSolver != null) {
            final var findBaseAllocsBackwards = new sync.pds.solver.nodes.Node<>(new NodeState(location), value);
            queryManager.getOrStartBackwardQuery(findBaseAllocsBackwards);
            final var basePointsToSet = queryManager.getPointsToGraph().getPointsToSet(location, value);
            final var flowFunctionState = copyFlowFunctionState();
            final QueryID bwdsID = new AliasQueryID(
                    new Query(containingSolver.initialQuery, containingSolver instanceof ForwardMerlinSolver),
                    new Query(findBaseAllocsBackwards, false),
                    originatingQueryValue);
            basePointsToSet.onAdd(TaggedHandler.create(bwdsID, alloc -> {
                final var newFlowFunctions = copyFromFlowFunctionState(flowFunctionState);
                containingSolver.withFlowFunctions(newFlowFunctions, () -> handler.accept(alloc));
            }));
        }
    }
}
