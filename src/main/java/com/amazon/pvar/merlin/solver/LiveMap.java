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

import com.amazon.pvar.merlin.livecollections.LiveSet;
import com.amazon.pvar.merlin.livecollections.Scheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class LiveMap<K, V> {

    private final Scheduler scheduler;

    protected LiveMap(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    private final Map<K, LiveSet<V>> backingMap = new HashMap<>();

    public static <K,V> LiveMap<K, V> create(Scheduler scheduler) {
        return new LiveMap<>(scheduler);
    }

    public synchronized void put(K k, V v) {
        this.get(k).add(v);
    }

    public synchronized LiveSet<V> get(K k) {
        return backingMap.computeIfAbsent(k, key -> LiveSet.create(scheduler));
    }

    public synchronized Collection<LiveSet<V>> values() {
        return backingMap.values();
    }

}
