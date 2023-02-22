package com.amazon.pvar.tspoc.merlin.ir;

import dk.brics.tajs.flowgraph.jsnodes.CallNode;

import java.util.Objects;

/**
 * An ad-hoc data flow fact to work around the issue that method calls in TAJS are represented
 * by call nodes with an invalid function register, but with a property string instead. `MethodCall(callNode)`
 * refers to the function invokes by `callNode` and is only introduced when resolving callees of
 * such call nodes. This allows starting callee queries uniformly by starting from an appropriate
 * initial SPDS state.
 *
 * A method call data flow fact is only expected to be generated at the same call node that it
 * is referring to and is immediately translated into a push rule by `BackwardsFlowFunctions`. */
public final class MethodCall extends Value {
    private final CallNode callNode;

    public MethodCall(CallNode callNode) {
        assert FlowgraphUtils.isMethodCallWithStaticProperty(callNode);
        this.callNode = callNode;
    }

    public CallNode getCallNode() {
        return callNode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodCall that = (MethodCall) o;
        return Objects.equals(callNode, that.callNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callNode);
    }
}
