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

package com.amazon.pvar.merlin.solver;

import com.amazon.pvar.merlin.DebugUtils;
import com.amazon.pvar.merlin.ir.Allocation;
import com.amazon.pvar.merlin.ir.FunctionAllocation;
import com.amazon.pvar.merlin.ir.MethodCall;
import com.amazon.pvar.merlin.ir.Register;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.ir.*;
import com.amazon.pvar.merlin.livecollections.LiveCollection;
import com.amazon.pvar.merlin.livecollections.LiveSet;
import com.amazon.pvar.merlin.livecollections.Scheduler;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.Node;

import java.util.Objects;

/**
 * This class stores points-to information collected during the course of an analysis.
 *
 * Information is stored in two multimaps for easy bidirectional lookup.
 */
public class PointsToGraph {
    private final Scheduler scheduler;
    private final LiveMap<PointsToLocation, Allocation> pointsToLiveMap;

    private final LiveMap<Allocation, PointsToLocation> allocationLiveMap;

    public PointsToGraph(Scheduler scheduler) {
        this.scheduler = scheduler;
        pointsToLiveMap = LiveMap.create(scheduler);
        allocationLiveMap = LiveMap.create(scheduler);
    }

    /**
     * A Node/Value pair that tracks whether information at that point is complete
     */
    public static class PointsToLocation {

        private final Node location;
        private final Value value;


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

        @Override
        public String toString() {
            return value + "@" + location.getIndex() + ":" + location;
        }
    }


    /**
     * Get the known points-to set for the provided location
     * @param location
     * @return
     */

    public LiveSet<Allocation> getPointsToSet(PointsToLocation location) {
        return pointsToLiveMap.get(location);
    }

    /**
     * Get the known points-to set for the provided Node/Value pair
     * @param location
     * @param value
     * @return
     */

    public LiveSet<Allocation> getPointsToSet(Node location, Value value) {
        return getPointsToSet(new PointsToLocation(location, value));
    }

    /**
     * Get locations that may point to the provided allocation site
     * @param alloc
     * @return
     */
    public LiveSet<PointsToLocation> getKnownValuesPointingTo(Allocation alloc) {
        return allocationLiveMap.get(alloc);
    }

    /**
     * Get program call nodes that may invoke the provided function allocation
     * 
     * @param functionAlloc
     * @return
     */
    public LiveCollection<CallNode> getKnownFunctionInvocations(FunctionAllocation functionAlloc) {
        final var allocLiveMap = allocationLiveMap.get(functionAlloc);
        DebugUtils.debug("listening for function invocations of " + functionAlloc.getAllocationStatement() +
                " on " + allocLiveMap);
        return allocLiveMap
                .filter(ptl -> {
                    if (ptl.getLocation() instanceof CallNode callNode) {
                        if (ptl.getValue() instanceof Register register) {
                            return callNode.getFunctionRegister() != -1 &&
                                    callNode.getFunctionRegister() == register.getId() &&
                                    callNode.getBlock().getFunction().equals(register.getContainingFunction());
                        } else if (ptl.getValue() instanceof MethodCall methodCall) {
                            return methodCall.getCallNode().equals(callNode);
                        } else {
                            return false;
                        }
                    }
                    return false;
                })
                .map(ptl -> (CallNode) ptl.getLocation());

    }

    /**
     * Add a points-to fact to the graph
     * 
     * @param pointsToLocation
     * @param allocation
     */
    public void addPointsToFact(PointsToLocation pointsToLocation, Allocation allocation) {
        pointsToLiveMap.put(pointsToLocation, allocation);
        allocationLiveMap.put(allocation, pointsToLocation);
        DebugUtils.debug("[" + this + "]: Discovered points-to: " + pointsToLocation + " -> " + allocation);
    }

    /**
     * Add a points-to fact to the graph using a new PointsToLocation
     * @param location
     * @param value
     * @param allocation
     */
    public void addPointsToFact(Node location, Value value, Allocation allocation) {
        PointsToLocation ptl = new PointsToLocation(location, value);
        addPointsToFact(ptl, allocation);
    }

    public synchronized int allocationCount() {
        return pointsToLiveMap.values().stream().map(LiveSet::currentSize).reduce(0, Integer::sum);
    }

//    @Override
//    public String toString() {
//        return "PointsToGraph(allocationCount: " + allocationCount() + ")";
//    }
}
