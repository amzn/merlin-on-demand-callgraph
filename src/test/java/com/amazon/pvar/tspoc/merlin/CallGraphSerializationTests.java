package com.amazon.pvar.tspoc.merlin;

import com.amazon.pvar.tspoc.merlin.ir.FlowgraphUtils;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;

public class CallGraphSerializationTests extends AbstractCallGraphTest {

    @Test
    public void canSerializeCallGraphsToJson() throws FileNotFoundException {
        // arrange
        final var testGraph = new CallGraph();
        final var flowgraph = initializeFlowgraph("src/test/resources/js/callgraph/json-tests/callgraph-json-test.js");
        final var barFunc = FlowgraphUtils.getFunctionByName(flowgraph, "bar").get();
        final var callToFoo = (CallNode) FlowgraphUtils.allNodesInFunction(barFunc)
                .filter(abstractNode -> abstractNode instanceof CallNode callNode && callNode.getTajsFunctionName() == null)
                .findFirst()
                .get();
        final var fooFunc = FlowgraphUtils.getFunctionByName(flowgraph, "foo").get();
        testGraph.addEdge(callToFoo, fooFunc);
        final var gson = new Gson();
        final var expectedJson = gson.fromJson(new FileReader("src/test/resources/js/callgraph/json-tests/callgraph-json-test.json"),
                JsonElement.class);
        // We need to replace the expected file by its absolute path, since CallGraph.toJSON is expected
        // to produce absolute paths.
        final var firstEdge = expectedJson.getAsJsonObject().get("edges").getAsJsonArray().get(0);
        final var callee = firstEdge.getAsJsonObject().get("callee").getAsJsonObject();
        final var sourceFile = "src/test/resources/js/callgraph/json-tests/_babel/callgraph-json-test.js";
        final var absSourceFile = new File(sourceFile).getAbsolutePath();
        callee.add("file", new JsonPrimitive(absSourceFile));
        final var caller = firstEdge.getAsJsonObject().get("caller").getAsJsonObject();
        caller.add("file", new JsonPrimitive(absSourceFile));

        // act
        final var asJson = testGraph.toJSON();

        // assert
        Assert.assertEquals(expectedJson, asJson);
    }
}
