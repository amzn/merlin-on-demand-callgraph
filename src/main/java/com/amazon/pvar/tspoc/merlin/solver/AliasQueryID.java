package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.ir.Value;

public record AliasQueryID(Query initialQuery, Query subQuery, Value currentQueryValue) implements QueryID {
}
