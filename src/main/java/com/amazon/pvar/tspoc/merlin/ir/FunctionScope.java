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

import java.util.LinkedList;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Representation of the chain of nested functions (starting at the outermost scope) in which a variable is declared
 */
public class FunctionScope {

    private final LinkedList<Function> scopeChain = new LinkedList<>();

    public FunctionScope(Function initialFunction) {
        scopeChain.add(initialFunction);
        Function next = initialFunction.getOuterFunction();
        while (Objects.nonNull(next)) {
            scopeChain.add(next);
            next = next.getOuterFunction();
        }
    }

    public LinkedList<Function> getScopeChain() {
        return scopeChain;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FunctionScope that = (FunctionScope) o;
        return Objects.equals(scopeChain, that.scopeChain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeChain);
    }

    @Override
    public String toString() {
        return scopeChain
                .stream()
                .map(function -> function.toString().replace(' ','-'))
                .collect(Collectors.joining("."));
    }
}
