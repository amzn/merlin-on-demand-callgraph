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
import com.amazon.pvar.merlin.ir.MethodCall;
import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.Register;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.solver.AliasQueryID;
import com.amazon.pvar.merlin.solver.ForwardMerlinSolver;
import com.amazon.pvar.merlin.solver.MerlinSolver;
import com.amazon.pvar.merlin.solver.Query;
import com.amazon.pvar.merlin.solver.QueryID;
import com.amazon.pvar.merlin.solver.QueryManager;
import com.amazon.pvar.merlin.ir.*;
import com.amazon.pvar.merlin.livecollections.LiveCollection;
import com.amazon.pvar.merlin.livecollections.LiveSet;
import com.amazon.pvar.merlin.livecollections.TaggedHandler;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import dk.brics.tajs.flowgraph.jsnodes.NodeVisitor;
import dk.brics.tajs.util.Pair;
import com.amazon.pvar.merlin.solver.*;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.CallPopNode;
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

    protected final QueryManager queryManager;
    protected final FlowFunctionContext context;
    protected final Set<State> nextStates;
    // TODO: This is only ever null in flow function unit tests. This could be avoided by using
    // a mock object in the flow function tests.
    @Nullable
    protected final MerlinSolver containingSolver;

    // Sanity check to ensure each flow function instance is used for exactly one transfer
    // function application, ensuring that no mutable state is shared across multiple invocations
    protected boolean transferApplied = false;


    public AbstractFlowFunctions(MerlinSolver containingSolver, QueryManager queryManager, FlowFunctionContext context) {
        this.containingSolver = containingSolver;
        this.queryManager = queryManager;
        this.context = context;
        this.nextStates = new HashSet<>();
    }

    /**
     * Given a set of allocations, return the corresponding functions for function allocations.
     */
    public static LiveCollection<Function> allocationsToFunctions(LiveCollection<Allocation> allocations) {
        return allocations.filter(alloc -> alloc instanceof FunctionAllocation)
                .map(alloc -> ((DeclareFunctionNode) alloc.getAllocationStatement()).getFunction());
    }

    private void addNextState(State nextState) {
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
     */
    public final Set<State> computeNextStates() {
        // If this assertion fails, we are inadvertently reusing the same flow function
        // instance.
        assert !transferApplied;
        transferApplied = true;
        final var node = context.currentPDSNode().stmt().getNode();
        final var val = context.queryValue();
        final var directionLabel = (this instanceof BackwardFlowFunctions) ? "bwd" : "fwd";
        final var lineNum = (node.getSourceLocation() != null)
                ? "L" + node.getSourceLocation().getLineNumber()
                : "L?";
        DebugUtils.debug(directionLabel + "+" + val + ": traversing node: " + node + "[" + node.getIndex() +
                "@" + node.getBlock().getFunction() + "]   " + lineNum);
        node.visitBy(this);
        return nextStates;
    }

    protected void addStandardNormalFlow(Node next) {
        addNextState(makeSPDSNode(next, context.queryValue()));
    }

    protected void addSingleState(Node n, Value v) {
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

    public static void logUnsoundness(Node node) {
       logUnsoundness(node, "");
    }

    public static void logUnsoundness(Node node, String message) {
//        System.err.println("Warning - Unsoundness from unhandled language feature at:\n" +
//                "\t" + node.getSourceLocation().toUserFriendlyString(true) + "\n" + message);
    }

    /**
     * Given a variable name and the function in which it is used, statically determine the function that declares the
     * variable
     *
     * @param varName
     * @param usageScope
     * @return
     */
    protected static Function getDeclaringScope(String varName, Function usageScope) {
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
//        System.err.println(errMsg + "\nFalling back to containing function");
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
    public LiveCollection<CallNode> findInvocationsOfFunction(Function function) {
        return findInvocationsOfFunctionWithQuery(function).getFirst();
    }

    public Pair<LiveCollection<CallNode>, Query> findInvocationsOfFunctionWithQuery(Function function) {
        DeclareFunctionNode functionDeclaration = function.getNode();
        FunctionAllocation alloc = new FunctionAllocation(functionDeclaration);
        sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                new NodeState(functionDeclaration),
                alloc
        );
        final var solver = queryManager.getOrStartForwardQuery(initialQuery);
        final var query = new Query(initialQuery, true);
//        var result = solver.getPointsToGraph().getKnownFunctionInvocations(alloc);
        final var result = queryManager.getCallGraph().getInvocationsOf(function);
        return Pair.make(result, query);
    }

    /**
     * Find all functions that could be call targets of the provided CallNode
     *
     * @param n
     * @return
     */
    public static LiveCollection<Function> resolveFunctionCall(CallNode n, QueryManager queryManager) {
        return resolveFunctionCallWithQueries(n, queryManager).getFirst();
    }

    public static List<Query> queriesToResolveFunctionCall(CallNode n) {
        // TODO: reduce duplication with resolveFunctionCallWithQueries
        final var querySet = new ArrayList<Query>();
        if (n.getTajsFunctionName() != null) {
            return querySet;
        }
        if (n.getFunctionRegister() != -1) {
            final var funcReg = new Register(n.getFunctionRegister(), n.getBlock().getFunction());
            final var predecessors = FlowgraphUtils.predecessorsOf(n).toList();
            for (var predecessor : predecessors) {
                final sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                        new NodeState(predecessor),
                        funcReg
                );
                querySet.add(new Query(initialQuery, false));
            }
            return querySet;
        } else if (n.getPropertyString() != null) {
            // Method call
            final var methodCall = new MethodCall(n);
            final sync.pds.solver.nodes.Node<NodeState, Value> query = new sync.pds.solver.nodes.Node<>(
                    new NodeState(n),
                    methodCall
            );
            querySet.add(new Query(query, false));
            return querySet;
        } else {
            DebugUtils.warn("Unhandled: method calls to dynamic field of object");
            return querySet;
        }
    }

    public static Pair<LiveCollection<Function>, Set<Query>> resolveFunctionCallWithQueries(CallNode n, QueryManager queryManager) {

        final var querySet = new HashSet<Query>();
        if (n.getTajsFunctionName() != null) {
            return Pair.make(LiveSet.create(queryManager.scheduler()), querySet); // don't try to resolve TAJS functions
        }
        if (n.getFunctionRegister() != -1) {
            final var funcReg = new Register(n.getFunctionRegister(), n.getBlock().getFunction());
            final var predecessors = FlowgraphUtils.predecessorsOf(n).toList();
            if (predecessors.size() == 1) {
                final var predecessor = predecessors.get(0);
                final sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                        new NodeState(predecessor),
                        funcReg
                );
                querySet.add(new Query(initialQuery, false));
                final var calleeLiveSet = queryManager.getCallGraph().getCalleesOf(n);
                final var solver = queryManager.getOrStartBackwardQuery(initialQuery, Optional.of(n), calleeLiveSet);
                return Pair.make(calleeLiveSet, querySet);
            } else {
                final var calleeLiveSet = queryManager.getCallGraph().getCalleesOf(n);
                for (var predecessor : predecessors) {
                    final sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                            new NodeState(predecessor),
                            funcReg
                    );
                    final var solver = queryManager.getOrStartBackwardQuery(initialQuery, Optional.of(n), calleeLiveSet);
                    querySet.add(new Query(initialQuery, false));
                }

                return Pair.make(calleeLiveSet, querySet);
            }
        } else if (n.getPropertyString() != null) {
            // Method call
            final var methodCall = new MethodCall(n);
            final sync.pds.solver.nodes.Node<NodeState, Value> query = new sync.pds.solver.nodes.Node<>(
                    new NodeState(n),
                    methodCall
            );
            final var calleeLiveSet = queryManager.getCallGraph().getCalleesOf(n);
            final var solver = queryManager.getOrStartBackwardQuery(query, Optional.of(n), calleeLiveSet);
            querySet.add(new Query(query, false));
            return Pair.make(calleeLiveSet, querySet);
//            return Pair.make(LiveSet.create(queryManager.scheduler()), querySet);
        } else {
            DebugUtils.warn("Unhandled: method calls to dynamic field of object");
            return Pair.make(LiveSet.create(queryManager.scheduler()), querySet);
        }
    }

    protected static Register syntheticRegisterForMethodCall(CallNode callNode) {
        // should only be called with method calls
        assert FlowgraphUtils.isMethodCallWithStaticProperty(callNode);
        // TODO: figure out if this ad-hoc register allocation is guaranteed to be unique
        return new Register(-100 + -1 * callNode.getIndex(), callNode.getBlock().getFunction());
    }

    protected Collection<Node> getPredecessors(Node n) {
        return FlowgraphUtils.predecessorsOf(n).collect(Collectors.toSet());
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
        if (containingSolver != null) {
            subquery.onAdd(TaggedHandler.create(queryID, result -> {
                handler.accept(result, this);
            }));
        }
    }

    public final void withAllocationSitesOf(Node location, Value value, Consumer<Allocation> handler, Value originatingQueryValue) {
        if (containingSolver != null) {
            final var preds = FlowgraphUtils.predecessorsOf(location);
            queryManager.registerPropertyAccessQuery(location);
            preds.forEach(pred -> {
                    final var findBaseAllocsBackwards = new sync.pds.solver.nodes.Node<>(new NodeState(pred), value);
                    queryManager.getOrStartBackwardQuery(findBaseAllocsBackwards, Optional.empty());
                    queryManager.registerQueryDependency(containingSolver.initialQueryWithDirection(),
                            new Query(findBaseAllocsBackwards, false));
                    final var basePointsToSet = queryManager.getPointsToGraph().getPointsToSet(pred, value);
                    final QueryID bwdsID = new AliasQueryID(
                            new Query(containingSolver.initialQuery, containingSolver instanceof ForwardMerlinSolver),
                            new Query(findBaseAllocsBackwards, false),
                            originatingQueryValue);
                    basePointsToSet.onAdd(TaggedHandler.create(bwdsID, handler));
                });
        }
    }

    // Precondition: called only on flow function instance at a CallNode
    public abstract void handleUnresolvedCall();
}
