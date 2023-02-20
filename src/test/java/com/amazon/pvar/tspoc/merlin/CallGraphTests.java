package com.amazon.pvar.tspoc.merlin;

import com.amazon.pvar.tspoc.merlin.experiments.Main;
import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.amazon.pvar.tspoc.merlin.solver.QueryManager;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import sync.pds.solver.nodes.Node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Parameterized test suite creating call graph tests automatically from .js files in
 * `src/test/resources/js/callgraph/callgraph-tests`.
 * <p>
 * Each file is scanned for two kinds of special comments to generate tests:
 * <p>
 * - `// callees: n_1, n_2, .., n_k` on lines containing exactly one call,
 * where `n_i` is a line number referring to the line an expected callee is defined on.
 * <p>
 * - `// callers: n_1, n_2, .., n_k` on the same line as a function declaration where `n_i` is a line number
 * referring to the line of a call to the function.
 */
@RunWith(Parameterized.class)
public final class CallGraphTests {

    public static String formatFlowGraphFile(FlowGraph flowGraph) {
        return new File(flowGraph.getMain().getSourceLocation().getLocation().getFile()).getName();
    }
    private CallGraphTest test;


    public CallGraphTests(CallGraphTest cgt) {
        this.test = cgt;
        BasicConfigurator.configure();
    }

    private void runFindCalleeTest(FindCallees findCallees) {
        org.apache.log4j.Logger.getRootLogger().setLevel(Level.OFF);
        final var queryManager = new QueryManager();
        final var callNode = findCallees.callNode();
        if (callNode.getFunctionRegister() == -1) {
            throw new RuntimeException("not supported yet");
        }
        final Value calleeQueryValue = new Register(callNode.getFunctionRegister(), callNode.getBlock().getFunction());
        final var initialQuery = new Node<>(
                new NodeState(callNode),
                calleeQueryValue
        );
        final var solver = queryManager.getOrStartBackwardQuery(initialQuery);
        solver.solve();
        queryManager.scheduler().waitUntilDone();
        final var callGraph = queryManager.getCallGraph();
        final var actualCallees = callGraph
                .edgeSet()
                .stream()
                .filter(edge -> edge.getCallSite().equals(callNode))
                .map(CallGraph.Edge::getCallTarget)
                .collect(Collectors.toSet());
        InterproceduralPointsToTests.printCallGraph(callGraph);
        final var callFuncPointsTo = queryManager.getPointsToGraph().getPointsToSet(callNode, calleeQueryValue).toJavaSet();
        InterproceduralPointsToTests.printPointsTo(calleeQueryValue, callNode, callFuncPointsTo);
        assertThat(actualCallees, equalTo(findCallees.expectedCallees()));
    }

    private void runFindCallerTest(FindCallers findCallers) {
        org.apache.log4j.Logger.getRootLogger().setLevel(Level.OFF);
        final var queryManager = new QueryManager();
        final var calleeFunc = findCallers.function();
        final Value funcAlloc = new FunctionAllocation(calleeFunc.getNode());
        final var initialQuery = new Node<>(
                new NodeState(calleeFunc.getNode()),
                funcAlloc
        );
        final var solver = queryManager.getOrStartForwardQuery(initialQuery);
        solver.solve();
        queryManager.scheduler().waitUntilDone();
        final var callGraph = queryManager.getCallGraph();
        final var actualCallers = callGraph
                .edgeSet()
                .stream()
                .filter(edge -> edge.getCallTarget().equals(calleeFunc))
                .map(CallGraph.Edge::getCallSite)
                .collect(Collectors.toSet());
        InterproceduralPointsToTests.printCallGraph(callGraph);
        assertThat(actualCallers, equalTo(findCallers.expectedCallers()));
    }

    @Test
    public void runCallGraphTest() {
        if (test instanceof FindCallees findCallees) {
            runFindCalleeTest(findCallees);
        } else if (test instanceof FindCallers findCallers) {
            runFindCallerTest(findCallers);
        }
        System.out.println(test);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> collectTestCases() {
        final var testDir = new File("src/test/resources/js/callgraph/callgraph-tests/");
        return Stream.of(Objects.requireNonNull(testDir.listFiles()))
                .sorted()
                .filter(file -> file.getName().endsWith(".js"))
//                .filter(file -> file.toString().contains("test.js"))
                // uncomment and adjust above line to debug individual test cases
                .flatMap(CallGraphTests::findTestsInFile)
                .map(cgt -> new Object[]{cgt})
                .toList();
    }

    private static Set<Function> parseCallees(FlowGraph flowGraph, String expectedCallees) {
        return Arrays.stream(expectedCallees.split(", *"))
                .map(Integer::parseInt)
                .map(calleeLine -> {
                    final var calleeFuncDecl = (DeclareFunctionNode) ensureOneElementIn(FlowgraphUtils
                            .allNodes(flowGraph)
                            .filter(node -> node instanceof DeclareFunctionNode && node.getSourceLocation().getLineNumber() == calleeLine),
                            "Ambiguous or missing expected callee for " + expectedCallees);
                    return calleeFuncDecl.getFunction();
                })
                .collect(Collectors.toSet());
    }


    private static Set<CallNode> parseCallers(FlowGraph flowGraph, String expectedCallers) {
        if (expectedCallers.isBlank()) {
            return new HashSet<>();
        }
        return Arrays.stream(expectedCallers.split(", *"))
                .map(Integer::parseInt)
                .map(callerLine -> (CallNode) ensureOneElementIn(FlowgraphUtils.allNodes(flowGraph)
                            .filter(node -> node instanceof CallNode && node.getSourceLocation().getLineNumber() == callerLine),
                            "Ambiguous or missing caller for " + expectedCallers))
                .collect(Collectors.toSet());
    }

    private static <T> T ensureOneElementIn(Stream<T> stream, String error) {
        final var elems = stream.toList();
        if (elems.size() != 1) {
            throw new RuntimeException("Expected " + elems + " to contain only one element. " + error);
        }
        return elems.get(0);
    }


    final private static Pattern calleePattern = Pattern.compile(".*// callees: *([0-9, ]*)$");
    final private static Pattern callerPattern = Pattern.compile(".*// callers: *([0-9, ]*)$");

    private static Stream<CallGraphTest> findTestsInFile(File file) {
        final var flowGraph = Main.flowgraphWithoutBabel(file.getAbsolutePath(), true);
        try (final var bufReader = new BufferedReader(new FileReader(file))) {
            final var tests = new ArrayList<CallGraphTest>();
            final var lines = bufReader.lines().toList();
            for (int i = 1; i <= lines.size(); i++) {
                final var line = lines.get(i - 1);
                if (line.trim().startsWith("//")) {
                    continue;
                }
                final var currentLineNumber = i; // needed because the line number is used in the filter lambda
                final var calleeMatcher = calleePattern.matcher(line);
                final var callerMatcher = callerPattern.matcher(line);
                if (calleeMatcher.matches()) {
                    // Find corresponding call nodes:
                    final var callNode = (CallNode) ensureOneElementIn(
                            FlowgraphUtils.allNodes(flowGraph)
                                    .filter(node -> node instanceof CallNode && node.getSourceLocation().getLineNumber() == currentLineNumber),
                            "Ambiguous or missing call node corresponding to callee query " + currentLineNumber + ": " + line);
                    tests.add(new FindCallees(flowGraph, callNode, parseCallees(flowGraph, calleeMatcher.group(1))));
                } else if (callerMatcher.matches()) {
                    final var funcDecl = ensureOneElementIn(flowGraph
                                    .getFunctions()
                                    .stream()
                                    .filter(func -> func.getSourceLocation().getLineNumber() == currentLineNumber),
                            "Ambiguous or missing function declaration for caller query " + currentLineNumber + ": " + line);
                    tests.add(new FindCallers(flowGraph, funcDecl, parseCallers(flowGraph, callerMatcher.group(1))));
                }
            }
            return tests.stream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

sealed interface CallGraphTest {
}

record FindCallees(FlowGraph flowGraph, CallNode callNode, Set<Function> expectedCallees) implements CallGraphTest {
    @Override
    public String toString() {
        final var expectedCalleeNames = expectedCallees.stream().map(Function::toString).toList();
        final var file = CallGraphTests.formatFlowGraphFile(flowGraph);
        return file + ": find-callees(" + callNode.getSourceLocation().getLineNumber() + ":" +
                callNode + ") = { " + String.join(", ", expectedCalleeNames) + " }";
    }
}

record FindCallers(FlowGraph flowGraph, Function function, Set<CallNode> expectedCallers) implements CallGraphTest {
    @Override
    public String toString() {
        final var expectedCallerNames = expectedCallers.stream().map(caller -> caller.getSourceLocation().getLineNumber() + ": " + caller)
                .toList();
        final var file = CallGraphTests.formatFlowGraphFile(flowGraph);
        return file + ": find-callers(" + function + ") = { " + String.join(", ", expectedCallerNames) + " }";
    }
}
