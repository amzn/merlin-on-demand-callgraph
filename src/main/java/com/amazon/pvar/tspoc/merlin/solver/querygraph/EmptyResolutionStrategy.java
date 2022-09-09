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

package com.amazon.pvar.tspoc.merlin.solver.querygraph;

/**
 * A cycle resolution strategy that prints an error message ad gives up if a cycle is encountered
 */
public class EmptyResolutionStrategy implements CycleResolutionStrategy{

    @Override
    public void resolveCycle(QueryGraph.Cycle cycle) {
        System.err.println("A QueryGraph cycle was detected while using an empty cycle resolution strategy!");
        System.err.println("Dumping DOT representation of the Query Graph:");
        System.err.println();
        System.err.println(QueryGraph.getInstance().toDOTString());
        System.err.println();
        throw new RuntimeException("Empty cycle resolution strategy");
    }

}
