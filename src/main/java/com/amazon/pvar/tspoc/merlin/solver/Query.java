package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import sync.pds.solver.nodes.Node;

public record Query(Node<NodeState, Value> queryValue, boolean isForward) {
}
