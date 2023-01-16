package com.amazon.pvar.tspoc.merlin.experiments;

import java.util.Set;

public record SerializableCallGraph(
        Set<SerializableCallGraphEdge> edges
) {
}
