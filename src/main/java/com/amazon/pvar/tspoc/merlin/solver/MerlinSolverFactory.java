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

package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import sync.pds.solver.nodes.Node;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Factory for creating Merlin solvers.
 * Also manages the call graph and points-to graph
 */
public class MerlinSolverFactory {

    private static PointsToGraph pointsToGraph = new PointsToGraph();
    private static CallGraph callGraph = new CallGraph();
    private static Deque<MerlinSolver> activeSolverStack = new ArrayDeque<>();

    public static ForwardMerlinSolver getNewForwardSolver(Node<NodeState, Value> initialQuery) {
        return new ForwardMerlinSolver(callGraph, pointsToGraph, initialQuery);
    }

    public static BackwardMerlinSolver getNewBackwardSolver(Node<NodeState, Value> initialQuery) {
        return new BackwardMerlinSolver(callGraph, pointsToGraph, initialQuery);
    }

    public static void addNewActiveSolver(MerlinSolver solver) {
        activeSolverStack.push(solver);
    }

    public static MerlinSolver removeCurrentActiveSolver() {
        return activeSolverStack.pop();
    }

    public static MerlinSolver peekCurrentActiveSolver() {
        return activeSolverStack.peek();
    }

    public static void reset() {
        pointsToGraph = new PointsToGraph();
        callGraph = new CallGraph();
        activeSolverStack = new ArrayDeque<>();
    }
}
