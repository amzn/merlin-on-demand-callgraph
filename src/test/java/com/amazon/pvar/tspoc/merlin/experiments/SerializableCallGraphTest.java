package com.amazon.pvar.tspoc.merlin.experiments;

import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.HashSet;

public class SerializableCallGraphTest {

    @Test
    public void canBeParsedFromJSON() throws FileNotFoundException {
        // arrange
        final var sourceFile = "src/test/resources/js/callgraph/json-tests/_babel/callgraph-json-test.js";
        final var expectedEdgeSet = new HashSet<SerializableCallGraphEdge>();
        expectedEdgeSet.add(new SerializableCallGraphEdge(
                new Span(new Location(1, 1), new Location(1, 18), sourceFile),
                new Span(new Location(3, 3), new Location(3, 8), sourceFile)
        ));
        SerializableCallGraph expectedCallGraph = new SerializableCallGraph(expectedEdgeSet);
        final var testFile = "src/test/resources/js/callgraph/json-tests/callgraph-json-test.json";

        // act
        final var gson = new Gson();
        SerializableCallGraph fromJson = gson.fromJson(new FileReader(testFile), SerializableCallGraph.class);

        // assert
        Assert.assertEquals(expectedCallGraph, fromJson);
    }

}