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

import org.apache.commons.cli.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ExperimentOptions {

    // Default sink list is adapted from taser: https://github.com/cs-au-dk/taser/blob/master/src/DefaultPolicy.ts
    private static final String SINK_DEFAULT_LOCATION = "scripts/evaluation/sinks.txt";

    private static final Option analysisDir = Option.builder("d")
            .argName("dir")
            .hasArg()
            .longOpt("directory")
            .desc("The directory containing the .js files to be analyzed. One of -d or -f must be specified.")
            .build();

    private static final Option nodeSinkFile = Option.builder("s")
            .argName("sinkFile")
            .hasArg()
            .longOpt("node-sink-file")
            .desc("A file containing the list of method names that Merlin should consider as taint sinks. If this " +
                    "option is not provided, Merlin will look in scripts/evaluation/sinks.txt.")
            .build();

    private static final Option analysisFile = Option.builder("f")
            .argName("file")
            .hasArg()
            .longOpt("file")
            .desc("The .js file to be analyzed. One of -d or -f must be specified.")
            .build();

    private static final Option outputFile = Option.builder("o")
            .argName("output-file")
            .hasArg()
            .longOpt("output")
            .desc("The location to write Merlin's results")
            .required()
            .build();

    private static final Option jsonSummaryFile = Option.builder("j")
            .argName("json-file")
            .hasArg()
            .longOpt("json")
            .desc("Write result summary as JSON to file")
            .build();

    private static final Option dumpFlowGraph = Option.builder("fg")
            .longOpt("dump-flowgraph")
            .desc("Output the TAJS flowgraph representation of the program")
            .build();

    private static final Option help = Option.builder("h")
            .desc("print this help message")
            .build();

    private static final Option taintQueriesToAnalyze = Option.builder("tq")
            .longOpt("taint-query")
            .desc("If present, only analyze specified taint queries specified by index (can be repeated)")
            .hasArgs()
            .build();

    private static final Options opts = new Options()
            .addOption(analysisDir)
            .addOption(analysisFile)
            .addOption(dumpFlowGraph)
            .addOption(outputFile)
            .addOption(nodeSinkFile)
            .addOption(taintQueriesToAnalyze)
            .addOption(help);

    private static CommandLine commandLine;

    public static void parse(String[] args) {
        try {
            commandLine = (new DefaultParser()).parse(opts, args);
            if (!commandLine.hasOption("d") && !commandLine.hasOption("f")) {
                throw new ParseException("Missing -d/-f flag");
            }
        } catch (ParseException parseException) {
            (new HelpFormatter()).printHelp("Merlin", opts);
            System.exit(0);
        }
    }

    public static boolean isAnalyzeDirectory() {
        return commandLine.hasOption("d");
    }

    public static String getAnalysisDir() {
        return commandLine.getOptionValue("d");
    }

    public static String getAnalysisFile() {
        return commandLine.getOptionValue("f");
    }

    public static boolean dumpFlowGraph() {
        return commandLine.hasOption("fg");
    }

    public static String getOutputFile() {
        return commandLine.getOptionValue("o");
    }

    public static File getNodeSinkFile() {
        if (commandLine == null || !commandLine.hasOption("s")) {
            return new File(SINK_DEFAULT_LOCATION);
        } else {
            return new File(commandLine.getOptionValue("s"));
        }
    }

    public static List<Integer> getTaintQueriesToAnalyze() {
        return Arrays.stream(commandLine.getOptionValues("tq"))
                .map(Integer::parseInt)
                .toList();
    }

    public static Optional<String> getJsonSummaryFile() {
        return Optional.ofNullable(commandLine.getOptionValue("j"));
    }
}
