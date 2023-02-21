package com.amazon.pvar.tspoc.merlin.solver.flowfunctions;

import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import sync.pds.solver.nodes.Node;

public record FlowFunctionContext(Node<NodeState, Value> currentPDSNode) {
    public Value queryValue() {
        return currentPDSNode.fact();
    }
}
