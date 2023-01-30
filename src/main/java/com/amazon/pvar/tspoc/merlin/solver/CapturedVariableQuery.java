package com.amazon.pvar.tspoc.merlin.solver;

public record CapturedVariableQuery(
        Query initialQuery,
        Query subQuery
) implements QueryID {
}
