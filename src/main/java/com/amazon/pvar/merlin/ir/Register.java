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

package com.amazon.pvar.merlin.ir;

import dk.brics.tajs.flowgraph.Function;

import java.util.Objects;

/**
 * A wrapper for TAJS' integer-numbered registers that implements the SPDS Location interface.
 */
public class Register extends Value {

    private final int id;
    private final Function containingFunction;

    public Register(int id, Function containingFunction) {
        this.id = id;
        this.containingFunction = containingFunction;
    }

    public int getId() {
        return id;
    }

    public Function getContainingFunction() {
        return containingFunction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Register register = (Register) o;
        return id == register.id && containingFunction.equals(register.containingFunction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, containingFunction);
    }

    @Override
    public String toString() {
        return "v" + id + " in " + containingFunction.toString();
    }
}
