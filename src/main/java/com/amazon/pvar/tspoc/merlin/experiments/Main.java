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
import com.amazon.pvar.tspoc.merlin.solver.MerlinSolverFactory;
import com.amazon.pvar.tspoc.merlin.solver.querygraph.QueryGraph;
import dk.brics.tajs.flowgraph.FlowGraph;
import dk.brics.tajs.flowgraph.jsnodes.CallNode;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import sync.pds.solver.nodes.Node;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

public class Main {

    public static synchronized FlowGraph flowGraphForProgram(String jsFile, boolean debugFlag) {
        dk.brics.tajs.options.Options.get().disableControlSensitivity();
        dk.brics.tajs.options.Options.get().enableTest();
        String[] inputs = { jsFile };
        var analysis = dk.brics.tajs.Main.init(inputs, null);
        var flowGraph = analysis.getSolver().getFlowGraph();
        return flowGraph;
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

    private static void runExperiment(String filename, FileWriter outputWriter) {
        CallGraph fullCg = new CallGraph();
        boolean debugFlag = ExperimentOptions.dumpFlowGraph();
        FlowGraph flowGraph = flowGraphForProgram(filename, debugFlag);
        Set<Node<NodeState, Value>> callSiteQueries = ExperimentUtils.getAllCallSiteQueries(flowGraph);
        int count = callSiteQueries.size();
        if (count > ExperimentUtils.Statistics.getMaxQueries()) {
            ExperimentUtils.Statistics.setMaxQueries(count);
        }
        ExperimentUtils.Timer<Node<NodeState, Value>> timer = new ExperimentUtils.Timer<>();
        timer.start();
        callSiteQueries.forEach(query -> {
            ExperimentUtils.Statistics.incrementTotalQueries();
            QueryGraph.reset();
            MerlinSolverFactory.reset();
            BackwardMerlinSolver solver = MerlinSolverFactory.getNewBackwardSolver(query);
            QueryGraph.getInstance().setRoot(solver);
            MerlinSolverFactory.addNewActiveSolver(solver);
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
            try {
                outputWriter.write("Known call graph:\n");
                outputWriter.write(solver.getCallGraph().toString() + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            solver.getCallGraph().forEach(fullCg::addEdge);
            timer.split(query);
            long split = timer.getSplit(query);
            try {
                outputWriter.write("Solver finished in " + split + "ms\n\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        timer.stop();
        ExperimentUtils.Statistics.incrementTotalTime(timer.getTotalElapsed());
        ExperimentUtils.Statistics.incrementCGEdgesFound(fullCg.size());
        try {
            outputWriter.write("Full CG for program:\n");
            outputWriter.write(fullCg.toString() + "\n");
            outputWriter.write("Total elapsed time: " + timer.getTotalElapsed() + "ms\n");
            outputWriter.write("Mean query time: " + timer.getTotalElapsed() / count + "ms\n\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void updateCG(BackwardMerlinSolver solver, Node<NodeState, Value> query) {
        Collection<Allocation> allocs = solver
                .getPointsToGraph()
                .getPointsToSet(query.stmt().getNode(), query.fact());
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
