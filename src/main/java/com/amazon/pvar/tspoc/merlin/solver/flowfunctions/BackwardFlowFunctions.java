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
import dk.brics.tajs.flowgraph.jsnodes.*;
import dk.brics.tajs.js2flowgraph.FlowGraphBuilder;
import sync.pds.solver.SyncPDSSolver;

import java.util.*;
import java.util.stream.Collectors;

public class BackwardFlowFunctions extends AbstractFlowFunctions {

    /**
     * Store node predecessor maps for each function as needed to avoid duplicating computation
     */
    private final Map<Function, Map<AbstractNode, Set<AbstractNode>>> predecessorMapCache = new HashMap<>();

    public BackwardFlowFunctions(CallGraph callGraph) {
        super(callGraph);
    }

    /**
     * Kill flow of the computation result at binary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(BinaryOperatorNode n) {
        Set<Value> killed = new HashSet<>();
        killed.add(usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction())));
        usedRegisters.computeIfAbsent(n.getArg1Register(), id -> new Register(id, n.getBlock().getFunction()));
        usedRegisters.computeIfAbsent(n.getArg2Register(), id -> new Register(id, n.getBlock().getFunction()));
        killAt(n, killed);
    }

    /**
     * Update the register map with registers discovered at this node and propagate the value
     *
     * @param n
     */
    @Override
    public void visit(CallNode n) {
        java.util.function.Function<Integer, Register> newRegisterLambda =
                id -> new Register(id, n.getBlock().getFunction());
        usedRegisters.computeIfAbsent(n.getResultRegister(), newRegisterLambda);
        usedRegisters.computeIfAbsent(n.getFunctionRegister(), newRegisterLambda);
        usedRegisters.computeIfAbsent(n.getBaseRegister(), newRegisterLambda);
        int numArgs = n.getNumberOfArgs();
        for (int i = 0; i < numArgs; i++) {
            usedRegisters.computeIfAbsent(n.getArgRegister(i), newRegisterLambda);
        }

        // propagate across the call site
        Set<Value> killed = new HashSet<>();
        killed.add(usedRegisters.get(n.getResultRegister()));
        killAt(n, killed);

        // If this is a call to an internal TAJS function, don't try to resolve it
        if (Objects.nonNull(n.getTajsFunctionName())) {
            return;
        }

        // propagate the assigned value to the return value of possibly invoked functions, if necessary
        if (getQueryValue().equals(usedRegisters.get(n.getResultRegister()))) {
            Collection<Function> targetFunctions = resolveFunctionCall(n);
            targetFunctions.forEach(targetFunction -> {
                Node returnNode = ((Node) targetFunction.getOrdinaryExit().getLastNode());
                Register returnReg = new Register(1, targetFunction);
                addCallPushState(returnNode, returnReg, n);
                usedRegisters.clear();
            });
        }
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(CatchNode n) {
        treatAsNop(n);
    }

    /**
     * Add the result register to the map of known registers and kill flow, unless the constant node denotes the start
     * of the current procedure. If we are at the start of the procedure, handle the interprocedural flow.
     *
     * @param n
     */
    @Override
    public void visit(ConstantNode n) {
        if (n.getResultRegister() == 1 && getQueryValue() instanceof Variable queryVar) {
            handleflowToFunctionEntry(n, queryVar);
        }

        Set<Value> killed = new HashSet<>();
        killed.add(usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction())));
        killAt(n, killed);
        usedRegisters.remove(n.getResultRegister());
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(DeletePropertyNode n) {
        treatAsNop(n);
    }

    @Override
    public void visit(BeginWithNode n) {
        usedRegisters.computeIfAbsent(n.getObjectRegister(), id -> new Register(id, n.getBlock().getFunction()));
        treatAsNop(n);
        logUnsoundness(n);
    }

    /**
     * TODO
     * @param n
     */
    @Override
    public void visit(ExceptionalReturnNode n) {
        treatAsNop(n);
    }

    /**
     * If this is a top-level function declaration (i.e. we are in main and the function declaration does not
     * assign to a register), kill flow at the function variable.
     *
     * Otherwise, kill flow of the result register at the newly created function
     *
     * @param n
     */
    @Override
    public void visit(DeclareFunctionNode n) {
        Set<Value> killed = new HashSet<>();

        // If a function declaration does not assign to a register, the result register is -1
        if (n.getResultRegister() == -1) {
            Variable newVar = new Variable(
                    n.getFunction().getName(),
                    getDeclaringScope(n.getFunction().getName(), n.getBlock().getFunction())
            );
            declaredVariables.add(newVar);
            killed.add(newVar);
            killAt(n, killed);
            declaredVariables.remove(newVar);
        } else {
            killed.add(usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction())));
            killAt(n, killed);
            usedRegisters.remove(n.getResultRegister());
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
     * is handled by the predecessor relation.
     *
     * @param n
     */
    @Override
    public void visit(IfNode n) {
        usedRegisters.computeIfAbsent(n.getConditionRegister(), id -> new Register(id, n.getBlock().getFunction()));
        treatAsNop(n);
    }

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
        Set<Value> killed = new HashSet<>();
        killed.add(usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction())));
        killAt(n, killed);
        usedRegisters.remove(n.getResultRegister());
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
     * Propagate dataflow through the nop.
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
     * Propagate data from the result register to the read variable, killing the variable's previous flow
     *
     * @param n
     */
    @Override
    public void visit(ReadVariableNode n) {
        Set<Value> killed = new HashSet<>();
        Register resultReg = usedRegisters
                .computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction()));
        Register baseReg = usedRegisters
                .computeIfAbsent(n.getResultBaseRegister(), id -> new Register(id, n.getBlock().getFunction()));
        Variable read = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction())
        );
        declaredVariables.add(read);
        killed.add(resultReg);
        killed.add(baseReg);
        killAt(n, killed);

        // Add register -> variable flow if necessary
        if (getQueryValue().equals(resultReg)) {
            genSingleNormalFlow(n, read);
        } else if (getQueryValue().equals(baseReg)) {
            genSingleNormalFlow(n, read);
        }
    }

    /**
     * The backward flow function across a return node is essentially the same as a nop node:
     * all known values in the scope are propagated across with normal flow.
     * @param n
     */
    @Override
    public void visit(ReturnNode n) {
        treatAsNop(n);
    }

    /**
     * TODO
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
     * Kill flow of the computation result at unary operator nodes
     *
     * @param n
     */
    @Override
    public void visit(UnaryOperatorNode n) {
        Set<Value> killed = new HashSet<>();
        killed.add(usedRegisters.computeIfAbsent(n.getResultRegister(), id -> new Register(id, n.getBlock().getFunction())));
        usedRegisters.computeIfAbsent(n.getArgRegister(), id -> new Register(id, n.getBlock().getFunction()));
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
     * Propagate data from the written variable to the argument register, killing the register's previous flow.
     *
     * @param n
     */
    @Override
    public void visit(WriteVariableNode n) {
        Set<Value> killed = new HashSet<>();
        Register argRegister = usedRegisters
                .computeIfAbsent(n.getValueRegister(), id -> new Register(id, n.getBlock().getFunction()));
        Variable write = new Variable(
                n.getVariableName(),
                getDeclaringScope(n.getVariableName(), n.getBlock().getFunction())
        );
        declaredVariables.add(write);
        killed.add(write);
        killAt(n, killed);

        // Add register -> variable flow if necessary
        if (getQueryValue().equals(write)) {
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
    protected void treatAsNop(Node n) {
        getPredecessors(n)
                .forEach(this::addStandardNormalFlow);
    }

    /**
     * Propagate dataflow across Node n, killing flow at the specified values
     *  @param n
     * @param killed
     */
    @Override
    protected void killAt(Node n, Set<Value> killed) {
        getPredecessors(n)
                .forEach(predecessor -> addStandardNormalFlow(predecessor, killed));
    }

    protected void genSingleNormalFlow(Node n, Value v) {
        getPredecessors(n)
                .forEach(predecessor -> addSingleState(predecessor, v));
    }

    private void handleflowToFunctionEntry(ConstantNode entryNode, Variable queryVar) {
        Function containingFunction = entryNode.getBlock().getFunction();
        if (containingFunction.isMain()) {
            return;
        }
        usedRegisters.clear();
        Optional<String> paramName = containingFunction.getParameterNames().stream()
                .filter(name -> name.equals(queryVar.getVarName()))
                .findFirst();
        // Launch a forward query on the containing function to find possible invocations
        Collection<CallNode> invokes = findInvocationsOfFunction(containingFunction);

        if (paramName.isPresent()) {
            // continue the backward query from the argument passed to the invocation
            int paramIndex = containingFunction.getParameterNames().indexOf(paramName.get());
            invokes.forEach(invoke -> {
                try {
                    Register reg = new Register(invoke.getArgRegister(paramIndex), invoke.getBlock().getFunction());
                    addCallPopState(invoke, reg);
                } catch (ArrayIndexOutOfBoundsException e) {}
            });
        } else {
            // Step backward from the invocation
            Function declaringFunction = queryVar.getDeclaringFunction();
            usedRegisters.clear();
            if (declaringFunction.getParameterNames().contains(queryVar.getVarName())) {
                // if the variable being queried is a parameter, we need to do some additional work to resolve
                // what the parameter could point to.
                int paramIndex = declaringFunction.getParameterNames().indexOf(queryVar.getVarName());

                // For query value p, which is a parameter of some function,
                // consult the call graph to determine what p could point to
                Set<Value> argsPointingToQueryVar = callGraph
                        .getCallers(declaringFunction)
                        .stream()
                        .map(callNode -> {
                            try {
                                int registerID = callNode.getArgRegister(paramIndex);
                                return new Register(registerID, callNode.getBlock().getFunction());
                            } catch (ArrayIndexOutOfBoundsException e) {
                                // a call site may call a function with fewer than the required arguments
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                invokes.forEach(invoke -> {
                    argsPointingToQueryVar.forEach(argRegister -> {
                        addCallPopState(invoke, argRegister);
                    });
                });
            } else {
                invokes.forEach(invoke -> {
                    addCallPopState(invoke, queryVar);
                });
            }
        }
    }

    /**
     * Find all functions that could be call targets of the provided CallNode
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
        Collection<Function> callees = getPredecessors(n)
                .stream()
                .flatMap(predecessor -> {
                    sync.pds.solver.nodes.Node<NodeState, Value> initialQuery = new sync.pds.solver.nodes.Node<>(
                            new NodeState(predecessor),
                            usedRegisters.get(n.getFunctionRegister())
                    );
                    BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(initialQuery);
                    QueryGraph.getInstance().addEdge(MerlinSolverFactory.peekCurrentActiveSolver(), solver);
                    MerlinSolverFactory.addNewActiveSolver(solver);
                    solver.setFunctionQuery(true);
                    solver.solve();
                    MerlinSolverFactory.removeCurrentActiveSolver();
                    solver.getPointsToGraph().markLocationComplete(initialQuery.stmt().getNode(), initialQuery.fact());
                    return solver
                            .getPointsToGraph()
                            .getPointsToSet(predecessor, usedRegisters.get(n.getFunctionRegister()))
                            .stream()
                            .filter(alloc -> alloc instanceof FunctionAllocation)
                            .map(alloc -> ((DeclareFunctionNode) alloc.getAllocationStatement()).getFunction());
                })
                .collect(Collectors.toSet());
        FlowFunctionUtil.allCalleesFound.add(n);
        return callees;
    }

    private Collection<Node> getPredecessors(Node n) {
        return predecessorMapCache
                .computeIfAbsent(n.getBlock().getFunction(), FlowGraphBuilder::makeNodePredecessorMap)
                .get(n)
                .stream()
                .filter(abstractNode -> abstractNode instanceof Node)
                .map(abstractNode -> ((Node) abstractNode))
                .collect(Collectors.toSet());
    }

    private void addCallPushState(Node entryNode, Value entryValue, Node callSite) {
        addSinglePushState(
                entryNode,
                entryValue,
                callSite,
                SyncPDSSolver.PDSSystem.CALLS
        );
    }

    private void addCallPopState(Node callSite, Value argValue) {
        addSinglePopState(callSite, argValue, SyncPDSSolver.PDSSystem.CALLS);
    }
}