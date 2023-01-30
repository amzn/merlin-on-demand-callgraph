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

package com.amazon.pvar.tspoc.merlin.ir;

import dk.brics.tajs.flowgraph.Function;

import java.util.Objects;

/**
 * A Wrapper for TAJS's string-based representation of variables that implements the SPDS Location interface.
 * Variables of the same name declared in different functions are differentiated by their scope chain
 */
public class Variable extends Value {

    private final String varName;
    private final FunctionScope scope;

    public Variable(String varName, Function declaringFunction) {
        this.varName = varName;
        this.scope = new FunctionScope(declaringFunction);
    }

    public String getVarName() {
        return varName;
    }

    public FunctionScope getScope() {
        return scope;
    }

    public Function getDeclaringFunction() {
        return scope.getScopeChain().getFirst();
    }

    /**
     * Determine if this variable is visible in (i.e. not shadowed by another variable of the same name) in the
     * provided function
     *
     * @param function
     * @return
     */
    public boolean isVisibleIn(Function function) {
        Function varDeclaringFunction = getDeclaringFunction();
        Function current = function;
        while (Objects.nonNull(current) && !Objects.equals(current, varDeclaringFunction)) {
            if (current.getParameterNames().contains(varName) ||
                current.getVariableNames().contains(varName)) {
                // This variable is shadowed by another variable with the same name in an inner scope
                return false;
            }
            current = current.getOuterFunction();
        }
        // If current is null, we reached the outermost scope without encountering the variable's declaring scope
        // Otherwise, the variable is visible
        return !Objects.isNull(current);
    }

    public boolean capturedIn(Function function) {
        return isVisibleIn(function);
        // TODO: implement properly and only return true if variable
        // occurs in body of function
    }

    @Override
    public String toString() {
        return "'" + scope + "." + varName + "'";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Variable variable = (Variable) o;
        return Objects.equals(varName, variable.varName) && Objects.equals(scope, variable.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(varName, scope);
    }
}
