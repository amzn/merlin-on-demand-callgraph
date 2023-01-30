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

import dk.brics.tajs.flowgraph.jsnodes.NewObjectNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;

import java.util.Objects;

/**
 * A wrapper class for object allocation sites. In the TAJS IR, there is a dedicated node type for object allocations,
 * so we choose NewObjectNode as the representative type for object allocation sites.
 *
 * We distinguish allocation sites from other types of values because, for many kinds of analyses, we can terminate
 * the analysis once an allocation site is reached.
 */
public class ObjectAllocation extends Register implements Allocation {

    private final NewObjectNode allocationStatement;

    public ObjectAllocation(NewObjectNode allocationStatement) {
        super(allocationStatement.getResultRegister(), allocationStatement.getBlock().getFunction());
        this.allocationStatement = allocationStatement;
    }

    @Override
    public String toString() {
        return "ObjAlloc@" + allocationStatement;
    }

    @Override
    public NewObjectNode getAllocationStatement() {
        return allocationStatement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        ObjectAllocation that = (ObjectAllocation) o;
        return Objects.equals(allocationStatement, that.allocationStatement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), allocationStatement);
    }
}
