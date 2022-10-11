package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.livecollections.LiveSet;
import com.amazon.pvar.tspoc.merlin.livecollections.Scheduler;

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
        return backingMap.computeIfAbsent(k, key -> new LiveSet<>(scheduler));
    }

}
