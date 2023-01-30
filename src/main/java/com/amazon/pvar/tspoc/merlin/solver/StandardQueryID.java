package com.amazon.pvar.tspoc.merlin.solver;

public record StandardQueryID(
        Query initialQuery,
        Query subQuery,
        boolean inUnbalancedPopListener,
        boolean resolvesAliasing
) implements QueryID {
    public StandardQueryID(Query initialQuery, Query subQuery, boolean inUnbalancedPopListener) {
        this(initialQuery, subQuery, inUnbalancedPopListener, false);
    }
}

