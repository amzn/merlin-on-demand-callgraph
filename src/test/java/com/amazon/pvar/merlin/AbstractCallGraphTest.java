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

package com.amazon.pvar.merlin;

import com.amazon.pvar.merlin.experiments.Main;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import org.apache.log4j.BasicConfigurator;
import org.junit.Before;

public class AbstractCallGraphTest {

    private static final boolean DEBUG_FLOWGRAPH = true;

    public AbstractCallGraphTest() {
        super();
        BasicConfigurator.configure();
    }

    @Before
    public void init() {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
    }

    public FlowGraph initializeFlowgraph(String filename) {
        return Main.flowGraphForProgram(filename, DEBUG_FLOWGRAPH);
    }

    public Node getNodeByIndex(int index, FlowGraph flowGraph) {
        return flowGraph.getFunctions().stream()
                .flatMap(function -> function.getBlocks().stream())
                .flatMap(basicBlock -> basicBlock.getNodes().stream())
                .filter(n -> n instanceof Node)
                .map(n -> ((Node) n))
                .filter(n -> n.getIndex() == index)
                .findAny()
                .orElseThrow();
    }

}
