/*
 * Copyright 2022-2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.pvar.merlin.experiments;

import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.Value;
import com.amazon.pvar.merlin.solver.BackwardMerlinSolver;
import com.amazon.pvar.merlin.solver.Query;
import com.amazon.pvar.merlin.solver.QueryManager;
import dk.brics.tajs.flowgraph.FlowGraph;
import org.apache.log4j.Level;
import sync.pds.solver.nodes.Node;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {

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

    private static synchronized FlowGraph flowgraphForProgram_(String jsFileRel, boolean debugFlag, boolean useBabel) {
        Path merlinRootDir = Paths.get(".").toAbsolutePath().normalize(); // pwd should be the root, merlin-on-demand-callgraph directory
        Path jsFile = merlinRootDir.resolve(jsFileRel);

        // set up options for TAJS Flowgraph
        dk.brics.tajs.options.Options.get().disableControlSensitivity();
        dk.brics.tajs.options.Options.get().enableTest();

        final var inputs = new ArrayList<String>();
        inputs.add(jsFile.toAbsolutePath().toString());
        if (useBabel) {
            Path tajsRootDir = merlinRootDir.resolve(Path.of("tajs_vr"));
            String tajsConfigFileStr = "";
            try {
                tajsConfigFileStr = makeTAJSConfigFile(tajsRootDir).getAbsolutePath().toString();
            } catch (IOException e) {
                throw new RuntimeException("Could not generate configuration for tajsVR flowgraph construction: " + e.getMessage());
            }
            inputs.add("-babel");
            inputs.add("-config");
            inputs.add(tajsConfigFileStr);
        }
        final var analysis = dk.brics.tajs.Main.init(inputs.toArray(new String[] {}), null);
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

    /**
     * Helper function to extract a flowgraph from a given file without babel transpilation.
     * */
    public static synchronized FlowGraph flowgraphWithoutBabel(String jsFile, boolean debugFlag) {
        return flowgraphForProgram_(jsFile, debugFlag, false);
    }


    public static synchronized FlowGraph flowGraphForProgram(String jsFileRel, boolean debugFlag) {
        return flowgraphForProgram_(jsFileRel, debugFlag, true);
    }

    public static void main(String[] args) {
        ExperimentOptions.parse(args);
        if (ExperimentOptions.isAnalyzeDirectory()) {
            Path directory = Paths.get(ExperimentOptions.getAnalysisDir());
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directory, "*.js");
                    FileWriter resultWriter = new FileWriter(ExperimentOptions.getOutputFile())) {
                directoryStream.forEach(jsFile -> {
                    ExperimentUtils.Statistics.incrementTotalFiles();
                    try {
                        resultWriter.write("Analyzing " + jsFile + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    runExperiment(jsFile.toString(), resultWriter);
                });
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            String filename = ExperimentOptions.getAnalysisFile();
            try (FileWriter resultWriter = new FileWriter(ExperimentOptions.getOutputFile())) {
                runExperiment(filename, resultWriter);
            } catch (IOException e) {
                e.printStackTrace();
            }
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
    }

    private static void runExperiment(String jsFile, FileWriter outputWriter) {
        boolean debugFlag = ExperimentOptions.dumpFlowGraph();
        if (debugFlag) {
            org.apache.log4j.Logger.getRootLogger().setLevel(Level.DEBUG);
        }
        FlowGraph flowGraph = flowGraphForProgram(jsFile, debugFlag);
        List<Node<NodeState, Value>> taintQueries = ExperimentUtils.getTaintQueries(flowGraph);
        int count = taintQueries.size();
        if (count == 0) {
            System.err.println("No queries detected for " + jsFile);
            System.exit(1);
        } else {
            System.err.println("Detected queries: " + taintQueries);
        }
        ExperimentUtils.Statistics.incrementTotalFiles();
        if (count > ExperimentUtils.Statistics.getMaxQueries()) {
            ExperimentUtils.Statistics.setMaxQueries(count);
        }
        ExperimentUtils.Timer<Node<NodeState, Value>> timer = new ExperimentUtils.Timer<>();
        timer.start();
        final var queryManager = QueryManager.of(flowGraph);
        final List<Node<NodeState, Value>> queriesToAnalyze;
        if (!ExperimentOptions.getTaintQueriesToAnalyze().isEmpty()) {
            queriesToAnalyze = new ArrayList<>();
            for (final var queryIdx: ExperimentOptions.getTaintQueriesToAnalyze()) {
                if (queryIdx >= taintQueries.size()) {
                    throw new IllegalArgumentException("Query index " + queryIdx + " out of range [0.." + (taintQueries.size() - 1) + "]");
                }
                queriesToAnalyze.add(taintQueries.get(queryIdx));
            }
        } else {
            queriesToAnalyze = taintQueries;
        }
        queriesToAnalyze.forEach(query -> {
            ExperimentUtils.Statistics.incrementTotalQueries();
            BackwardMerlinSolver solver = queryManager.getOrStartBackwardQuery(query, Optional.empty());
            System.err.println("solver for " + query + " has been started");
            try {
                outputWriter.write("Query: " + query + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
//            solver.solve();
//            queryManager.solve();
            try {
                outputWriter.write("Known call graph:\n");
//                outputWriter.write(solver.getCallGraph().toString() + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            timer.split(query);
            long split = timer.getSplit(query);
            try {
                outputWriter.write("Solver finished in " + split + "ms\n\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        queryManager.solve(false);
        timer.stop();
        taintQueries.forEach(query -> {
            final var errors = queryManager.errorsImpactingQuery(new Query(query, false));
            if (!errors.isEmpty()) {
                    System.err.println("Query " + query + " encountered exceptions in queries it depends on:");
                    for (Map.Entry<Query, Set<Exception>> entry : errors.entrySet()) {
                        Query subQuery = entry.getKey();
                        Set<Exception> exceptions = entry.getValue();
                        System.err.println("- " + subQuery + ": " + exceptions);
                    }
                } else {
                    System.err.println("Query " + query + " resolved successfully");
                }
            final var results = queryManager.getPointsToGraph().getPointsToSet(query.stmt().getNode(), query.fact()).toJavaSet();
            System.err.println("Results for query: " + query + ": " + results);
        });
        ExperimentUtils.Statistics.incrementTotalTime(timer.getTotalElapsed());
        ExperimentUtils.Statistics.incrementCGEdgesFound(queryManager.getCallGraph().size());
        try {
            outputWriter.write("CG for program:\n");
            outputWriter.write(queryManager.getCallGraph() + "\n");
            outputWriter.write("Total elapsed time: " + timer.getTotalElapsed() + "ms\n");
            outputWriter.write("Mean query time: " + timer.getTotalElapsed() / count + "ms\n\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
