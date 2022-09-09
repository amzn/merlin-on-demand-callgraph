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

import com.amazon.pvar.tspoc.merlin.ir.Allocation;
import com.amazon.pvar.tspoc.merlin.ir.FunctionAllocation;
import com.amazon.pvar.tspoc.merlin.ir.Register;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class stores points-to information collected during the course of an analysis.
 *
 * Information is stored in two multimaps for easy bidirectional lookup.
 */
public class PointsToGraph {

    /**
     * A Node/Value pair that tracks whether information at that point is complete
     */
    public static class PointsToLocation {

        private final Node location;
        private final Value value;
        private boolean hasCompleteInformation = false;


        public PointsToLocation(Node location, Value value) {
            this.location = location;
            this.value = value;
        }

        public Node getLocation() {
            return location;
        }

        public Value getValue() {
            return value;
        }

        public boolean hasCompleteInformation() {
            return hasCompleteInformation;
        }

        public void setHasCompleteInformation(boolean hasCompleteInformation) {
            this.hasCompleteInformation = hasCompleteInformation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PointsToLocation that = (PointsToLocation) o;
            return Objects.equals(location, that.location) && Objects.equals(value, that.value);
        }

        /**
         * We omit hasCompleteInformation from the hash to avoid accidental duplicate entries in the backing maps
         *
         * @return
         */
        @Override
        public int hashCode() {
            return Objects.hash(location, value);
        }
    }

    private final Multimap<PointsToLocation, Allocation> pointsToBackingMap = HashMultimap.create();
    private final Multimap<Allocation, PointsToLocation> allocationBackingMap = HashMultimap.create();
    private final Table<Node, Value, PointsToLocation> ptlTable = HashBasedTable.create();

    /**
     * Get the known points-to set for the provided location
     * @param location
     * @return
     */
    public Collection<Allocation> getPointsToSet(PointsToLocation location) {
        return pointsToBackingMap.get(location);
    }

    /**
     * Get the known points-to set for the provided Node/Value pair
     * @param location
     * @param value
     * @return
     */
    public Collection<Allocation> getPointsToSet(Node location, Value value) {
        return getPointsToSet(ptlTable.get(location, value));
    }

    /**
     * Get locations that may point to the provided allocation site
     * @param alloc
     * @return
     */
    public Collection<PointsToLocation> getKnownValuesPointingTo(Allocation alloc) {
        return allocationBackingMap.get(alloc);
    }

    /**
     * Get program call nodes that may invoke the provided function allocation
     * @param functionAlloc
     * @return
     */
    public Set<CallNode> getKnownFunctionInvocations(FunctionAllocation functionAlloc) {
        return allocationBackingMap
                .get(functionAlloc)
                .stream()
                .filter(ptl -> {
                    if (ptl.getLocation() instanceof CallNode callNode &&
                        ptl.getValue() instanceof Register register) {
                        return callNode.getFunctionRegister() != -1 &&
                                callNode.getFunctionRegister() == register.getId() &&
                                callNode.getBlock().getFunction().equals(register.getContainingFunction());
                    }
                    return false;
                })
                .map(ptl -> (CallNode) ptl.getLocation())
                .collect(Collectors.toSet());
    }

    /**
     * Add a points-to fact to the graph
     * @param pointsToLocation
     * @param allocation
     */
    public void addPointsToFact(PointsToLocation pointsToLocation, Allocation allocation) {
        pointsToBackingMap.put(pointsToLocation, allocation);
        allocationBackingMap.put(allocation, pointsToLocation);
    }

    /**
     * Add a points-to fact to the graph using a new PointsToLocation
     * @param location
     * @param value
     * @param allocation
     */
    public void addPointsToFact(Node location, Value value, Allocation allocation) {
        PointsToLocation ptl = new PointsToLocation(location, value);
        ptlTable.put(location, value, ptl);
        addPointsToFact(ptl, allocation);
    }

    public void markLocationComplete(Node location, Value value) {
        if (!ptlTable.contains(location, value)) {
            ptlTable.put(location, value, new PointsToLocation(location, value));
        }
        Objects.requireNonNull(ptlTable.get(location, value)).setHasCompleteInformation(true);
    }

    public boolean isLocationComplete(Node location, Value value) {
        if (!ptlTable.contains(location, value)) {
            return false;
        }
        return Objects.requireNonNull(ptlTable.get(location, value)).hasCompleteInformation();
    }
}
