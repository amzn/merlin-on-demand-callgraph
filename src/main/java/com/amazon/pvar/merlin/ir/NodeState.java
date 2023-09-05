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

import dk.brics.tajs.flowgraph.SourceLocation;
import dk.brics.tajs.flowgraph.jsnodes.Node;
import dk.brics.tajs.flowgraph.jsnodes.NopNode;
import wpds.interfaces.Empty;
import wpds.interfaces.Location;

import java.util.Objects;

/**
 * A wrapper class for Node that implements the Location interface, so that Nodes can be used by the SPDS framework.
 *
 * The SPDS framework requires that program points implement the "Location" interface, which is not intuitive as
 * program points are primarily used as stack elements. It would be more conceptually intuitive for program points
 * to implement the "State" interface provided by SPDS, but the SyncPDSSolver class actually does it's own wrapping
 * of program points as States.
 */
public class NodeState implements Location {

    private final Node node;

    public NodeState(Node node) {
        this.node = node;
    }

    public Node getNode() {
        return node;
    }

    /**
     * Implementation of a required Location method. It will not be used in this project, but "equals" is usually the
     * default implementation of this method in the SPDS framework.
     *
     * @param location
     * @return true if the locations are equal, false otherwise.
     */
    @Override
    public boolean accepts(Location location) {
        return this.equals(location);
    }

    public static EpsilonNodeState getEpsilon() {
        return EpsilonNodeState.instance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeState that = (NodeState) o;
        return Objects.equals(node, that.node);
    }

    @Override
    public int hashCode() {
        return Objects.hash(node);
    }

    @Override
    public String toString() {
        return node.toString() + "@" + node.getBlock().getFunction();
    }

    /**
     * SPDS uses "epsilon" stack symbols to represent transitions in the saturated P-Automaton that are taken without
     * any input symbol.
     *
     * For all singleton IR classes (like this one), it is important to make sure that the correct SPDS interface
     * is implemented. The SPDS solver performs instanceof checks against the following interfaces:
     *  - Empty
     *  - Wildcard
     *  - ExclusionWildcard
     * If the correct interface is not implemented on the IR classes passed to the SPDS solver, the analysis will not
     * propagate date flow correctly, and the issue will likely be very difficult to debug.
     */
    private static class EpsilonNodeState extends NodeState implements Empty {

        private static final EpsilonNodeState instance = new EpsilonNodeState();

        /**
         * The Node created here is an empty placeholder Nop node.
         */
        private EpsilonNodeState() {
            super(new NopNode(
                    new SourceLocation.SyntheticLocationMaker("")
                            .make(0,0,0,0)
            ));
        }

        @Override
        public String toString() {
            return "EpsilonNodeState";
        }
    }
}
