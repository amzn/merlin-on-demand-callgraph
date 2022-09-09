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

import org.apache.commons.cli.*;

public class ExperimentOptions {

    private static final Option analysisDir = Option.builder("d")
            .argName("dir")
            .hasArg()
            .longOpt("directory")
            .desc("The directory containing the .js files to be analyzed. One of -d or -f must be specified.")
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

    private static final Option dumpFlowGraph = Option.builder("fg")
            .longOpt("dump-flowgraph")
            .desc("Output the TAJS flowgraph representation of the program")
            .build();

    private static final Option help = Option.builder("h")
            .desc("print this help message")
            .build();

    private static final Options opts = new Options()
            .addOption(analysisDir)
            .addOption(analysisFile)
            .addOption(dumpFlowGraph)
            .addOption(outputFile)
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
}
