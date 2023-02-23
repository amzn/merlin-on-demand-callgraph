/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.pvar.tspoc.merlin.solver;

import com.amazon.pvar.tspoc.merlin.DebugUtils;
import com.amazon.pvar.tspoc.merlin.ir.NodeState;
import com.amazon.pvar.tspoc.merlin.ir.Property;
import com.amazon.pvar.tspoc.merlin.ir.Value;
import com.amazon.pvar.tspoc.merlin.solver.flowfunctions.AbstractFlowFunctions;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.util.Pair;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import sync.pds.solver.OneWeightFunctions;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.PushNode;
import wpds.impl.*;
import wpds.interfaces.Location;
import wpds.interfaces.State;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public abstract class MerlinSolver extends SyncPDSSolver<NodeState, Value, Property, Weight.NoWeight> {

    public final Node<NodeState, Value> initialQuery;
    protected final QueryManager queryManager;
    /**
     * Unresolved calls for which we added artificial data flows already.
     * */
    protected final Set<Pair<CallNode, Value>> handledUnresolvedCalls = new HashSet<>();

    /**
     * The SyncPDSSolver class requires WeightFunctions in the case that the analysis includes a weight domain.
     * Since we do not use a weight domain in our analysis, we just provide the default weight function implementation
     * defined in the SPDS framework. These weight functions essentially tell the framework that "this analysis does
     * not have a weight domain".
     */
    private final WeightFunctions<NodeState, Value, Property, Weight.NoWeight> fieldWeightFunction =
            new OneWeightFunctions<>(Weight.NO_WEIGHT_ONE);
    private final WeightFunctions<NodeState, Value, NodeState, Weight.NoWeight> callWeightFunction =
            new OneWeightFunctions<>(Weight.NO_WEIGHT_ONE);

    /**
     * We do not use summaries for this analysis so useCall/FieldSummaries and call/fieldSummaries are set to
     * false and default, respectively.
     *
     * We also set maxCallDepth, maxFieldDepth, and maxUnbalancedCallDepth to -1, which corresponds to unlimited depth.
     * If developing a tool for production use, these may be useful parameters to examine for the sake of performance.
     */
    public MerlinSolver(QueryManager queryManager, Node<NodeState, Value> initialQuery) {
        super(
                false,
                new SummaryNestedWeightedPAutomatons<>(),
                false,
                new SummaryNestedWeightedPAutomatons<>(),
                -1,
                -1,
                -1
        );
        this.queryManager = queryManager;
        this.initialQuery = initialQuery;
    }

    public PointsToGraph getPointsToGraph() {
        return queryManager.getPointsToGraph();
    }

    public CallGraph getCallGraph() {
        return queryManager.getCallGraph();
    }

    protected void updateCallGraph(Node<NodeState, Value> node, State nextState) {
        dk.brics.tajs.flowgraph.jsnodes.Node tajsNode = node.stmt().getNode();
        if (tajsNode instanceof CallNode callNode) {
            if (nextState instanceof PushNode pushNode) {
                if (pushNode.stmt() instanceof NodeState nodeState) {
                    Function nextFunction = nodeState.getNode().getBlock().getFunction();
                    if (!nextFunction.equals(tajsNode.getBlock().getFunction())) {
                        // Add a new CG edge if the current state and the next state are in different functions
                        queryManager.getCallGraph().addEdge(callNode, nextFunction);
                    }
                }
            }
        }
    }

    @Override
    public void applyCallSummary(NodeState nodeState, Value value, NodeState stmt1, NodeState stmt2, Value fact1) {
        // Not implemented because we are not using method summaries
    }

    @Override
    public WeightFunctions<NodeState, Value, Property, Weight.NoWeight> getFieldWeights() {
        return fieldWeightFunction;
    }

    @Override
    public WeightFunctions<NodeState, Value, NodeState, Weight.NoWeight> getCallWeights() {
        return callWeightFunction;
    }

    protected abstract AbstractFlowFunctions makeFlowFunctions(Node<NodeState, Value> currentPDSNode);

    @Override
    public synchronized void computeSuccessor(Node<NodeState, Value> node) {
        if (Objects.isNull(node.stmt().getNode())) {
            System.err.println("Warning: no predecessor statement found. " +
                    "The analysis may have reached the beginning of the program without finding an allocation site");
            return;
        }
        final var flowFunctions = makeFlowFunctions(node);
        for (final var nextNode : flowFunctions.nextNodes(node.stmt().getNode())) {
            if (nextNode instanceof CallNode callNode) {
                this.registerListener(updatedNode -> {
                    if (updatedNode.stmt().getNode().equals(callNode)) {
                        DebugUtils.debug("Listener called about update for " + callNode);
                        computeSuccessor(updatedNode);
                    }
                });
            }
        }
        final var nextStates = flowFunctions.computeNextStates();
        nextStates.forEach(nextState -> propagate(node, nextState));
    }

    @Override
    public Property epsilonField() {
        return Property.getEpsilon();
    }

    @Override
    public Property emptyField() {
        return Property.getEmpty();
    }

    @Override
    public NodeState epsilonStmt() {
        return NodeState.getEpsilon();
    }

    /**
     * TODO: Not required for minimal working example, but will be required for more interesting analyses
     * @return
     */
    @Override
    public Property exclusionFieldWildCard(Property property) {
        return null;
    }

    @Override
    public Property fieldWildCard() {
        return Property.getWildcard();
    }

    @Override
    public String toString() {
        return "MerlinSolver{" +
                "initialQuery=" + initialQuery +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerlinSolver that = (MerlinSolver) o;
        return Objects.equals(initialQuery, that.initialQuery);
    }

    @Override
    public int hashCode() {
        return Objects.hash(initialQuery);
    }

    public abstract String getQueryString();

    public final QueryID getQueryID(Node<NodeState, Value> subQuery, boolean isSubQueryForward, boolean inUnbalancedPopListener) {
        return getQueryID(subQuery, isSubQueryForward, inUnbalancedPopListener, false);
    }

    public abstract QueryID getQueryID(Node<NodeState, Value> subQuery, boolean isSubQueryForward, boolean inUnbalancedPopListener, boolean resolvesAliasing);

    public abstract void solve();

    @Override
    public synchronized final void propagate(Node<NodeState, Value> curr, State s) {
        super.propagate(curr, s);
    }

    public static BufferedImage visualizeCallPDS(WeightedPushdownSystem<NodeState, INode<Value>, Weight.NoWeight> callPDS) {
        // TODO: merge with visualizeFieldPDS once done
        final var graph = new DefaultDirectedGraph<Node<NodeState, Value>, CallEdge>(CallEdge.class);
        final Consumer<Node<NodeState, Value>> ensureVertexExists = (vertex) -> {
            if (!graph.containsVertex(vertex)) {
                graph.addVertex(vertex);
            }
        };
        for (final var rule: callPDS.getAllRules()) {
            if (rule instanceof PopRule<NodeState, INode<Value>, Weight.NoWeight> popRule) {

                continue;
            }
            final var src = new Node<>(new NodeState(rule.getL1().getNode()), rule.getS1().fact());
            final var dst = new Node<>(new NodeState(rule.getL2().getNode()), rule.getS2().fact());
            ensureVertexExists.accept(src);
            ensureVertexExists.accept(dst);
            final CallEdge edge;
            if (rule instanceof PushRule pushRule) {
                edge = new CallEdge(pushRule.getCallSite());
            } else if (rule instanceof PopRule<NodeState, INode<Value>, Weight.NoWeight> popRule) {
                edge = new CallEdge(null);
            } else {
                edge = new CallEdge();
            }
            graph.addEdge(src, dst, edge);
        }
        final var dotExporter = new DOTExporter<Node<NodeState, Value>, CallEdge>();
        dotExporter.setVertexAttributeProvider(vertex -> {
            final Map<String, Attribute> result = new HashMap<>();
            result.put("label", DefaultAttribute.createAttribute(vertex.toString()));
            return result;
        });
        dotExporter.setEdgeAttributeProvider(edge -> {
            final Map<String, Attribute> result = new HashMap<>();
            result.put("label", DefaultAttribute.createAttribute(edge.toString()));
            return result;
        });
        try {
            final var prefix = "callPDS";
            final var tempDotFile = File.createTempFile(prefix, ".dot");
            dotExporter.exportGraph(graph, tempDotFile);
            System.out.println("Wrote .dot graph to " + tempDotFile);
            tempDotFile.deleteOnExit();
            final var tempSVGFile = File.createTempFile(prefix, ".svg");
            final var procBuilder = new ProcessBuilder("dot", "-Tsvg", "-o" + tempSVGFile, tempDotFile.toString());
            System.out.println("SVG written to " + tempSVGFile);
            procBuilder.start().waitFor();
            java.awt.Desktop.getDesktop().open(tempSVGFile);
            return ImageIO.read(tempSVGFile);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static class CallEdge {
        private final Optional<Location> callSite;

        public CallEdge(Location callSite) {
            this.callSite = Optional.of(callSite);
        }

        public CallEdge() {
            this.callSite = Optional.empty();
        }

        @Override
        public String toString() {
            return callSite.toString();
        }
    }

    private String sanitizeDotName(String input) {
        return input.replace("\\\"", "?");
    }

    public BufferedImage visualizeFieldPDS(WeightedPushdownSystem<Property, INode<Node<NodeState, Value>>, Weight.NoWeight> fieldPDS) {
        final var graph = new DefaultDirectedGraph<Node<NodeState, Value>, FieldEdge>(FieldEdge.class);
        final Consumer<Node<NodeState, Value>> ensureVertexExists = (vertex) -> {
            if (!graph.containsVertex(vertex)) {
                graph.addVertex(vertex);
            }
        };
        for (final var rule: fieldPDS.getAllRules()) {
            final var src = rule.getS1().fact();
            final var dst = rule.getS2().fact();
            ensureVertexExists.accept(src);
            ensureVertexExists.accept(dst);
            final var edge = new FieldEdge(rule.getL1(), rule.getL2());
            graph.addEdge(src, dst, edge);
        }
        final var dotExporter = new DOTExporter<Node<NodeState, Value>, FieldEdge>();
        dotExporter.setVertexAttributeProvider(vertex -> {
            final Map<String, Attribute> result = new HashMap<>();
            result.put("label", DefaultAttribute.createAttribute(sanitizeDotName(vertex.toString())));
            return result;
        });
        dotExporter.setEdgeAttributeProvider(edge -> {
            final Map<String, Attribute> result = new HashMap<>();
            result.put("label", DefaultAttribute.createAttribute(sanitizeDotName(edge.toString())));
            return result;
        });
        try {
            final var prefix = "fieldPDS";
            final var tempDotFile = File.createTempFile(prefix, ".dot");
            dotExporter.exportGraph(graph, tempDotFile);
            System.out.println("Wrote .dot graph to " + tempDotFile);
            tempDotFile.deleteOnExit();
            final var tempSVGFile = File.createTempFile(prefix, ".svg");
            final var procBuilder = new ProcessBuilder("dot", "-Tsvg", "-o" + tempSVGFile, tempDotFile.toString());
            System.out.println("SVG written to " + tempSVGFile);
            final var result = procBuilder.start().waitFor();
            if (result != 0) {
                throw new RuntimeException("Failed to convert dot file to svg");
            }
            java.awt.Desktop.getDesktop().open(tempSVGFile);
            return ImageIO.read(tempSVGFile);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static class FieldEdge {
        Property prop1, prop2;
        public FieldEdge(Property prop1, Property prop2) {
            this.prop1 = prop1;
            this.prop2 = prop2;
        }

        @Override
        public String toString() {
            return "(" + prop1 + ", " + prop2 + ")";
        }

    }

    public void visualizePDSes() {
//        visualizeCallPDS(this.callingPDS);
        visualizeFieldPDS(this.fieldPDS);
    }

    /**
     * Add data flows for unresolved function calls to provide "less unsound" results
     * for unresolved methods.
     *
     * @return Returns true iff any new data flow was added */
    public final boolean addDataFlowsForUnresolvedFunctionCalls() {
        var changed = false;
        queryManager.scheduler().waitUntilDone();
        for (final var state : this.getReachedStates()) {
            if (state.stmt().getNode() instanceof CallNode callNode) {
                final var callees = AbstractFlowFunctions.resolveFunctionCall(callNode, queryManager).toJavaSet();
                final var callAndQuery = Pair.make(callNode, state.fact());
                if (callees.isEmpty() && !handledUnresolvedCalls.contains(callAndQuery)) {
                    AbstractFlowFunctions.logUnsoundness(callNode, "Treating unresolved function call as side-effect free");
                    // Add data flow for unresolved function call
                    final var flowFunctions = makeFlowFunctions(new Node<>(
                            new NodeState(callNode),
                            state.fact()
                    ));
                    flowFunctions.handleUnresolvedCall();
                    handledUnresolvedCalls.add(callAndQuery);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public synchronized int stateCount() {
        return getReachedStates().size();
    }
}
