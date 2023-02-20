package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.ir.FlowgraphUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CatchNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.ReadVariableNode;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Various utility functions for dealing with captured variables. */
public class CapturedVariableAnalysis {

    /**
     * Only lists functions capturing `variable` that are directly declared inside
     * the body of context, but not nested function declarations. */
    public static Set<DeclareFunctionNode> functionsCapturingVarIn(Function context, String variable) {
        return FlowgraphUtils.allNodesInFunction(context)
                .filter(node -> node instanceof DeclareFunctionNode && variableUsedInFunction(variable, ((DeclareFunctionNode) node).getFunction()))
                .map(node -> (DeclareFunctionNode)node)
                .collect(Collectors.toSet());
    }

    /**
     * Determine whether `var` occurs syntactically within `func`, including if `var` occurs inside
     * the body any function defined inside `func` (including transitively).
     *
     * Note that part partially shadowed variables are not allowed in ES5, for example the following
     * code would hoist the declaration of x to the top of f:
     *
     * ```
     * var x = 1;
     * function f() {
     *     var y = x;
     *     var x = 2;
     *     console.log(`y: ${y}`); // prints that y is undefined here.
     * }
     *
     * */
    public static boolean variableUsedInFunction(String variableName, Function func) {
        return freeVariablesIn(func).contains(variableName);
    }

    private static Set<String> boundVariablesIn(Function func) {
        final Set<String> result = new HashSet<>();
        if (func.getName() != null) {
            result.add(func.getName());
        }
        result.addAll(func.getVariableNames());
        return result;
    }

    private static Set<String> freeVariablesIn(Function func)  {
        return freeVariableMemoCache.getUnchecked(func);
    }

    private static LoadingCache<Function, Set<String>> freeVariableMemoCache = CacheBuilder.newBuilder()
            .weakKeys()
            .maximumSize(100)
            .build(CacheLoader.from(func -> {
                final var directlyUsedInFunc = FlowgraphUtils.allNodesInFunction(func)
                        .flatMap(node -> {
                            if (node instanceof ReadVariableNode readVar) {
                                return Stream.of(readVar.getVariableName());
                            } else if (node instanceof CatchNode catchNode) {
                                return Stream.of(catchNode.getVariableName());
                            } else {
                                return Stream.empty();
                            }
                        })
                        .filter(var -> !boundVariablesIn(func).contains(var));
                final var usedInDeclaredFunctionBody = FlowgraphUtils.allNodesInFunction(func)
                        .flatMap(node -> {
                            if (node instanceof DeclareFunctionNode declFun) {
                                return freeVariablesIn(declFun.getFunction()).stream();
                            } else {
                                return Stream.empty();
                            }
                        });
                final var resultSet = directlyUsedInFunc.collect(Collectors.toSet());
                resultSet.addAll(usedInDeclaredFunctionBody.collect(Collectors.toSet()));
                return resultSet;
            }));
}
