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
import com.amazon.pvar.tspoc.merlin.solver.BackwardMerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.amazon.pvar.tspoc.merlin.solver.MerlinSolverFactory;
import com.amazon.pvar.tspoc.merlin.solver.PointsToGraph;
import com.amazon.pvar.tspoc.merlin.solver.querygraph.QueryGraph;
import dk.brics.tajs.flowgraph.AbstractNode;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;
import sync.pds.solver.SyncPDSSolver;

import java.util.*;
import java.util.stream.Collectors;

public class ForwardFlowFunctions extends AbstractFlowFunctions {

    /**
     * Store node successor maps for each function as needed to avoid duplicating computation
     */
    private final Map<Function, Map<AbstractNode, Set<AbstractNode>>> successorMapCache = new HashMap<>();

    public ForwardFlowFunctions(CallGraph callGraph) {
        super(callGraph);
    }

    /**
     * Kill flow of the two arguments at binary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(BinaryOperatorNode n) {
        Set<Value> killed = new HashSet<>();
        usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction()));
        killed.add(usedRegisters.computeIfAbsent(n.getArg1Register(), id -> new Register(id, n.getBlock().getFunction())));
        killed.add(usedRegisters.computeIfAbsent(n.getArg2Register(), id -> new Register(id, n.getBlock().getFunction())));
        killAt(n, killed);
    }

    /**
     * Propagates values to the callee (if they are used in the callee),
     * or across the call site (if they are not used in the callee)
     * @param n
     */
    @Override
    public void visit(CallNode n) {

        // Update Registers
        java.util.function.Function<Integer, Register> newRegisterLambda =
                id -> new Register(id, n.getBlock().getFunction());
        usedRegisters.computeIfAbsent(n.getResultRegister(), newRegisterLambda);
        usedRegisters.computeIfAbsent(n.getFunctionRegister(), newRegisterLambda);
        usedRegisters.computeIfAbsent(n.getBaseRegister(), newRegisterLambda);
        int numArgs = n.getNumberOfArgs();
        for (int i = 0; i < numArgs; i++) {
            usedRegisters.computeIfAbsent(n.getArgRegister(i), newRegisterLambda);
        }

        // If this is a call to an internal TAJS function, don't try to resolve it
        if (Objects.nonNull(n.getTajsFunctionName()) ||
                n.getSourceLocation().getKind() == SourceLocation.Kind.SYNTHETIC) {
            treatAsNop(n);
            return;
        }

        Collection<Function> callTargets = resolveFunctionCall(n);

        callTargets.forEach(targetFunction -> {
            Node entryPoint = ((Node) targetFunction.getEntry().getFirstNode());
            // Propagate queryVal to the target function's environment, if necessary
            if (getQueryValue() instanceof Variable var) {
                // Check if the target function or any of its nested functions actually reference var
                // before propagating - prevents unnecessarily large points-to sets
                if (var.isVisibleIn(targetFunction)) {
                    SyntacticReferenceResolver resolver = new SyntacticReferenceResolver(targetFunction, var);
                    if (resolver.isVarReferencedInNestedScopes()) {
                        addCallPushState(entryPoint, var, n);
                    }
                }
            } else {
                // Propagate flow from arg registers to params in the callee
                for (int i = 0; i < numArgs; i++) {
                    Register argRegister = usedRegisters.get(n.getArgRegister(i));
                    try {
                        String paramName = targetFunction.getParameterNames().get(i);
                        Variable param = new Variable(paramName, targetFunction);
                        declaredVariables.add(param);
                        if (getQueryValue().equals(argRegister)) {
                            addCallPushState(entryPoint, param, n);
                        }
                    } catch (IndexOutOfBoundsException e) {
                        // Do nothing, if we pass an extra unused arg to a function, there's no need to propagate
                    }
                }
            }
        });

        // Propagate values across the call site
        treatAsNop(n);
        usedRegisters.clear();
    }

    /**
     * TODO: Exceptional flow is not currently tracked
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
        usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction()));
        treatAsNop(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(DeletePropertyNode n) {
        treatAsNop(n);
    }

    /**
     * Merlin does not handle "with" statements, but does flag this unsoundness
     * @param n
     */
    @Override
    public void visit(BeginWithNode n) {
        usedRegisters.computeIfAbsent(n.getObjectRegister(), id -> new Register(id, n.getBlock().getFunction()));
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO: Exceptional flow is not currently tracked
     * @param n
     */
    @Override
    public void visit(ExceptionalReturnNode n) {
        treatAsNop(n);
    }

    /**
     * Declared functions are treated the same as any other value in the program, but special care must be given
     * to top-level function declarations that are not assigned to a variable.
     * @param n
     */
    @Override
    public void visit(DeclareFunctionNode n) {
        if (n.getResultRegister() == -1) {
            // This function declaration was not assigned to a result register at creation
            Variable functionVariable = new Variable(n.getFunction().getName(), n.getBlock().getFunction());
            declaredVariables.add(functionVariable);
            FunctionAllocation alloc = new FunctionAllocation(n);
            usedRegisters.put(n.getFunction().getName().hashCode(), alloc);
            if (getQueryValue().equals(alloc)) {
                genSingleNormalFlow(n, functionVariable);
            }
            treatAsNop(n);
        } else {
            // This function declaration is a function expression, assigned to some result register
            usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction()));
            treatAsNop(n);
        }
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(BeginForInNode n) {
        treatAsNop(n);
    }

    /**
     * Add the condition register to the set of known registers and propagate all normal flows.  Note that branching
     * is handled by the successor relation.
     *
     * @param n
     */
    @Override
    public void visit(IfNode n) {
        usedRegisters.computeIfAbsent(n.getConditionRegister(), id -> new Register(id, n.getBlock().getFunction()));
        treatAsNop(n);
    }

    /**
     * Merlin does not handle "with" statements, but does flag this unsoundness
     * @param n
     */
    @Override
    public void visit(EndWithNode n) {
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(NewObjectNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(NextPropertyNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
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
     * TODO
     * @param n
     */
    @Override
    public void visit(ReadPropertyNode n) {
        treatAsNop(n);
    }

    /**
     * Propagate data from the read variable to the result register, killing previous flow at the register
     *
     * @param n
     */
    @Override
    public void visit(ReadVariableNode n) {
        Register result = usedRegisters
                .compute(n.getResultRegister(), (id, r) -> new Register(id, n.getBlock().getFunction()));
        Variable read = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction())
        );
        declaredVariables.add(read);
        Set<Value> killed = new HashSet<>();
        killed.add(result);
        killAt(n, killed);

        if (getQueryValue().equals(read)) {
            genSingleNormalFlow(n, result);
        }
    }

    /**
     * Propagate return flow back to possible call sites that call this function.
     *
     * Note that the SPDS framework handles context sensitivity and will not continue propagating data flow along
     * any paths where calls and returns are not properly matched.
     * @param n
     */
    @Override
    public void visit(ReturnNode n) {
        if (n.getBlock().getFunction().isMain()) {
            // end of program
            return;
        }
        Register result = usedRegisters
                .computeIfAbsent(n.getReturnValueRegister(), id -> new Register(id, n.getBlock().getFunction()));
        Collection<CallNode> possibleReturnSites = findInvocationsOfFunction(n.getBlock().getFunction());
        // Handle return value assignment
        if (getQueryValue().equals(result)) {
            possibleReturnSites.forEach(returnSite -> {
                Register returnReg = new Register(returnSite.getResultRegister(), returnSite.getBlock().getFunction());
                addCallPopState(
                        returnSite,
                        returnReg
                );
            });
        }

        // TODO: Handle return propagation of formal parameters
        //  and any other values visible but not declared in this function scope
    }

    /**
     * TODO: Exceptional flow is not currently tracked
     * @param n
     */
    @Override
    public void visit(ThrowNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
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
        Set<Value> killed = new HashSet<>();
        usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction()));
        killed.add(usedRegisters.computeIfAbsent(n.getArgRegister(), id -> new Register(id, n.getBlock().getFunction())));
        killAt(n, killed);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(DeclareVariableNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(WritePropertyNode n) {
        treatAsNop(n);
    }

    /**
     * Propagate data from the argument register to the written variable, killing previous flow at the variable
     *
     * @param n
     */
    @Override
    public void visit(WriteVariableNode n) {
        Register argRegister = usedRegisters
                .computeIfAbsent(n.getValueRegister(), id -> new Register(id, n.getBlock().getFunction()));
        Variable write = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction())
        );
        declaredVariables.add(write);
        Set<Value> killed = new HashSet<>();
        killed.add(write);
        killAt(n, killed);

        if (getQueryValue().equals(argRegister)) {
            genSingleNormalFlow(n, write);
        }
    }

    /**
     * Merlin does not handle eventdispatchernodes, but does flag this unsoundness
     * @param n
     */
    @Override
    public void visit(EventDispatcherNode n) {
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(EndForInNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(BeginLoopNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(EndLoopNode n) {
        treatAsNop(n);
    }

    @Override
    protected void killAt(Node n, Set<Value> killed) {
        getSuccessors(n)
                .forEach(node -> addStandardNormalFlow(node, killed));
    }

    @Override
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

    /**
     * Resolve possible functions pointed to by the function register at the call site by issuing a backward query
     *
     * @param n
     * @return
     */
    private Collection<Function> resolveFunctionCall(CallNode n) {
        if (FlowFunctionUtil.allCalleesFound.contains(n)) {
            return callGraph.getCallTargets(n);
        }
        PointsToGraph ptg = MerlinSolverFactory
                .peekCurrentActiveSolver()
                .getPointsToGraph();
        if (ptg.isLocationComplete(n, usedRegisters.get(n.getFunctionRegister()))) {
            return ptg.getPointsToSet(n, usedRegisters.get(n.getFunctionRegister()))
                    .stream()
                    .filter(alloc -> alloc instanceof FunctionAllocation)
                    .map(alloc -> ((DeclareFunctionNode) alloc.getAllocationStatement()).getFunction())
                    .collect(Collectors.toSet());
        }
        Node immediatePredecessor = ((Node) FlowGraphBuilder
                .makeNodePredecessorMap(n.getBlock().getFunction())
                .get(n)
                .stream()
                .findFirst()
                .orElseThrow());
        sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                new NodeState(immediatePredecessor),
                usedRegisters.computeIfAbsent(
                        n.getFunctionRegister(),
                        id -> new Register(id, n.getBlock().getFunction())
                )
        );
        BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
        QueryGraph.getInstance().addEdge(MerlinSolverFactory.peekCurrentActiveSolver(), solver);
        MerlinSolverFactory.addNewActiveSolver(solver);
        solver.setFunctionQuery(true);
        solver.solve();
        MerlinSolverFactory.removeCurrentActiveSolver();
        solver.getPointsToGraph().markLocationComplete(initialQuery.stmt().getNode(), initialQuery.fact());
        FlowFunctionUtil.allCalleesFound.add(n);
        return solver
                .getPointsToGraph()
                .getPointsToSet(immediatePredecessor, usedRegisters.get(n.getFunctionRegister()))
                .stream()
                .filter(alloc -> alloc instanceof FunctionAllocation)
                .map(alloc -> ((DeclareFunctionNode) alloc.getAllocationStatement()).getFunction())
                .collect(Collectors.toSet());
    }

    private Collection<Node> getSuccessors(Node n) {
        return successorMapCache
                .computeIfAbsent(
                        n.getBlock().getFunction(),
                        f -> invertMapping(FlowGraphBuilder.makeNodePredecessorMap(f))
                )
                .getOrDefault(n, Collections.emptySet())
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode))
                .collect(Collectors.toSet());
    }

    private void addCallPushState(Node entryNode, Value val, CallNode callSite) {
        addSinglePushState(
                entryNode,
                val,
                callSite,
                SyncPDSSolver.PDSSystem.CALLS
        );
    }

    private void addCallPopState(CallNode returnSite, Value valueToPropagate) {
        addSinglePopState(returnSite, valueToPropagate, SyncPDSSolver.PDSSystem.CALLS);
    }
}
