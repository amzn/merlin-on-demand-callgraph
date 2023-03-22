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

package com.amazon.pvar.tspoc.merlin.experiments;

import com.amazon.pvar.tspoc.merlin.ir.*;
import com.amazon.pvar.tspoc.merlin.solver.BackwardMerlinSolver;
import com.amazon.pvar.tspoc.merlin.solver.CallGraph;
import com.amazon.pvar.tspoc.merlin.solver.Query;
import com.amazon.pvar.tspoc.merlin.solver.QueryManager;
import com.google.gson.*;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import org.apache.log4j.Level;
import sync.pds.solver.nodes.Node;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.management.RuntimeErrorException;

public class Main {

    public record QueryResult(
        String queryName, 
        JsonElement queryCallGraph, 
        long queryRunTime
    ) {}
    public record ExperimentResult(
        String jsFile, 
        List<QueryResult> queryResults, 
        JsonElement experimentCallGraph, 
        long totalExperimentTime, 
        double meanQueryTime
    ) {}
    public record MerlinResult(
        String path, 
        List<ExperimentResult> experimentResults, 
        int fileCount, 
        int queryCount, 
        int queriesPerProgram, 
        int maxQueries, 
        int uniqueCallgraphEdges,
        long totalTime, 
        long timePerFile, 
        long timePerQuery
    ) {}

    // Create additional config values, see https://github.com/cs-au-dk/TAJS#environment-configuration for overview
    // and tajs_vs/src/dk/brics/tajs/TAJSEnvironmentConfig.java for all options
    public static File makeTAJSConfigFile(Path tajsRootDir) throws IOException {

        // Calculate path to tajs_vr/extras/babel/node_modules/.bin/babel ensuring that node_modules exists
        Path babelPathPrefix = (tajsRootDir.resolve(Path.of("extras", "babel", "node_modules"))).toAbsolutePath();
        if (!babelPathPrefix.toFile().exists()) {
            throw new IOException("Path to required node_modules - %s - does not exist. Did you forget to run `npm install` in %s"
                                  .formatted(babelPathPrefix.toString(), babelPathPrefix.getParent().toString())
                                  );
        }
        String babelCmd = System.getProperty("os.name").startsWith("Windows") ? "babel.cmd" : "babel";
        Path babelPath = babelPathPrefix.resolve(Path.of(".bin", babelCmd));

 
        // write a tajs.properties file to a temporary directory
        File config = File.createTempFile("tajs", ".properties");
        FileWriter writer = new FileWriter(config);
        writer.write("""
            tajs = %s
            babel = %s
        """.formatted(tajsRootDir.getParent().toAbsolutePath().toString(), babelPath.toAbsolutePath().toString()));
        writer.close();
 
        // return the new tajs.properties file
        return config;
    }

    /**
     * Helper function to extract a flowgraph from a given file without babel transpilation.
     * */
    public static synchronized FlowGraph flowgraphWithoutBabel(String jsFile, boolean debugFlag) {
        // TODO: reduce duplication in `flowgraphForProgram`
        final var analysis = dk.brics.tajs.Main.init(new String[] { jsFile }, null);
        final var flowGraph = analysis.getSolver().getFlowGraph();
        if (debugFlag) {
            final var flowgraphFile = jsFile + ".flowgraph";
            try {
                Files.writeString(Paths.get(flowgraphFile), flowGraph.toString());
                System.err.println("Flowgraph written to " + flowgraphFile);
            } catch (IOException e) {
                System.err.println("Failed to write flowgraph to file: " + flowgraphFile);
            }
        }
        return flowGraph;
    }


    public static synchronized FlowGraph flowGraphForProgram(String jsFileRel, boolean debugFlag) {

        Path merlinRootDir = Paths.get(".").toAbsolutePath().normalize(); // pwd should be the root, merlin-on-demand-callgraph directory
        Path jsFile = merlinRootDir.resolve(jsFileRel);

        // set up options for TAJS Flowgraph
        dk.brics.tajs.options.Options.get().disableControlSensitivity();
        dk.brics.tajs.options.Options.get().enableTest();

        Path tajsRootDir = merlinRootDir.resolve(Path.of("tajs_vr"));
        String tajsConfigFileStr = "";
        try {
            tajsConfigFileStr = makeTAJSConfigFile(tajsRootDir).getAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Could not generate configuration for tajsVR flowgraph construction: " + e.getMessage());
        }
        
        String[] inputs = { jsFile.toAbsolutePath().toString(), "-babel", "-config", tajsConfigFileStr };
        final var analysis = dk.brics.tajs.Main.init(inputs, null);
        final var flowGraph = analysis.getSolver().getFlowGraph();
        if (debugFlag) {
            final var flowgraphFile = jsFile + ".flowgraph";
            try {
                Files.writeString(Paths.get(flowgraphFile), flowGraph.toString());
                System.err.println("Flowgraph written to " + flowgraphFile);
            } catch (IOException e) {
                System.err.println("Failed to write flowgraph to file: " + flowgraphFile);
            }
        }
        return flowGraph;
    }

    public static void main(String[] args) {
        ExperimentOptions.parse(args);

        // either get the directory for the DirectoryStream, or turn the single file into 
        // a DirectoryStream by filtering the parent dir down to the single file
        Path directory = null; 
        String filter = "";
        if (ExperimentOptions.isAnalyzeDirectory()) {
            directory = Paths.get(ExperimentOptions.getAnalysisDir());
            filter = "*.js";
            
        } else {
            Path analysisFile = Paths.get(ExperimentOptions.getAnalysisFile());
            directory = analysisFile.getParent();
            filter = analysisFile.getFileName().toString();
        }

        List<ExperimentResult> experimentResults = new ArrayList();

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directory, filter)) {
            directoryStream.forEach(jsFile -> {
                System.out.println("Analyzing " + jsFile + "\n");

                ExperimentUtils.Statistics.incrementTotalFiles();
                ExperimentResult result = runExperiment(jsFile.toString(), new PrintWriter(System.out, true));
               
                experimentResults.add(result);
            });
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Printing statistics
        int fileCount = ExperimentUtils.Statistics.getTotalFiles();
        int queryCount = ExperimentUtils.Statistics.getTotalQueries();
        int cgEdges = ExperimentUtils.Statistics.getTotalCGEdges();
        int maxQueries = ExperimentUtils.Statistics.getMaxQueries();
        long timeElapsed = ExperimentUtils.Statistics.getTotalTimeMillis();
        long timePerFile = timeElapsed / fileCount;
        long timePerQuery = timeElapsed / queryCount;
        int queriesPerProgram = queryCount / fileCount;
        System.out.println();
        System.out.println();
        System.out.println("Programs analyzed:\t\t" + fileCount);
        System.out.println("Queries issued:\t\t\t" + queryCount);
        System.out.println("Queries per program:\t" + queriesPerProgram);
        System.out.println("Maximum Queries:\t\t" + maxQueries);
        System.out.println("Unique CG Edges found:\t" + cgEdges);
        System.out.println("Elapsed time:\t\t\t" + timeElapsed + "ms");
        System.out.println("Time per program:\t\t" + timePerFile + "ms");
        System.out.println("Time per query:\t\t\t" + timePerQuery + "ms");

        try {
            String name = directory.toString() + File.separator + filter;

            final var merlinResults = new MerlinResult(
                    name, experimentResults, fileCount, queryCount, 
                    queriesPerProgram, maxQueries, cgEdges, timeElapsed, timePerFile, timePerQuery
            );
            FileWriter outputWriter = new FileWriter(ExperimentOptions.getOutputFile());
            final var json = (new GsonBuilder().setPrettyPrinting().create()).toJson(merlinResults);
            outputWriter.write(json);
            outputWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static ExperimentResult runExperiment(String jsFile, Writer outputWriter) {
        boolean debugFlag = ExperimentOptions.dumpFlowGraph();
        if (debugFlag) {
            org.apache.log4j.Logger.getRootLogger().setLevel(Level.DEBUG);
        }
        FlowGraph flowGraph = flowGraphForProgram(jsFile, debugFlag);
        
        Set<Node<NodeState, Value>> taintQueries = ExperimentUtils.getTaintQueries(flowGraph);
        int count = taintQueries.size();
        if (count == 0) {
            System.err.println("FATAL: No queries detected for " + jsFile);
            System.exit(1);
        } else {
            System.err.println("Detected queries: " + taintQueries);
        }
        
        if (count > ExperimentUtils.Statistics.getMaxQueries()) {
            ExperimentUtils.Statistics.setMaxQueries(count);
        }
        CallGraph cg = new CallGraph();
        ExperimentUtils.Statistics.incrementTotalFiles();

        List<QueryResult> queryResults = new ArrayList();

        ExperimentUtils.Timer<Node<NodeState, Value>> timer = new ExperimentUtils.Timer<>();
        timer.start();
        final var queryManager = new QueryManager();
        taintQueries.forEach(query -> {
            ExperimentUtils.Statistics.incrementTotalQueries();
            BackwardMerlinSolver solver = queryManager.getOrCreateBackwardSolver(query);
            try {
                outputWriter.write("Query: " + query + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            solver.solve();
            // If the initial query was for a call site, add results of the query to the call graph
            if (ExperimentUtils.isCallSiteQuery(query)) {
                updateCG(solver, query);
            }
            CallGraph queryCallGraph = solver.getCallGraph();
            try {
                outputWriter.write("Known call graph:\n");
                outputWriter.write(queryCallGraph.toString() + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            queryCallGraph.forEach(cg::addEdge);
            timer.split(query);
            long split = timer.getSplit(query);
            try {
                outputWriter.write("Solver finished in " + split + "ms\n\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            queryResults.add(new QueryResult(query.toString(), queryCallGraph.toJSON(), split));
        });
        queryManager.solve();
        timer.stop();
        ExperimentUtils.Statistics.incrementTotalTime(timer.getTotalElapsed());
        ExperimentUtils.Statistics.incrementCGEdgesFound(cg.size());
        long totalExperimentTime = timer.getTotalElapsed();
        double meanQueryTime = timer.getTotalElapsed() / count;
        try {
            outputWriter.write("CG for program:\n");
            outputWriter.write(cg + "\n");
            outputWriter.write("Total elapsed time: " + totalExperimentTime + "ms\n");
            outputWriter.write("Mean query time: " + meanQueryTime + "ms\n\n");
            outputWriter.write("Callgraph for program as JSON:\n");
            outputWriter.write(cg.toJSON().toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new ExperimentResult(jsFile, queryResults, cg.toJSON(), totalExperimentTime, meanQueryTime);
    }

    private static void updateCG(BackwardMerlinSolver solver, Node<NodeState, Value> query) {
        Collection<Allocation> allocs = solver
                .getPointsToGraph()
                .getPointsToSet(query.stmt().getNode(), query.fact()).toJavaSet();
        allocs.stream()
                .filter(alloc -> alloc instanceof FunctionAllocation)
                .forEach(funcAlloc -> solver
                        .getCallGraph()
                        .addEdge(
                                ((CallNode) query.stmt().getNode()),
                                ((DeclareFunctionNode) funcAlloc.getAllocationStatement()).getFunction()
                        )
                );
    }
}
