#!/usr/bin/env ts-node

import { Command, Option, program } from 'commander';
import * as path from "path";
import { nodeProfTransformer } from './src/nodeprof-transformer';
import { runFullEvaluation } from './src/full-evaluation';
import { runDynCg, getGraalNodePath, runJam } from './src/callgraphs';
import { getBenchmarkMainFile, getBenchmarkModulePath, getDynCgsDir, getJamCgsDir, modulesToBenchmark } from './src/benchmark-utils';

const jamOption = new Option('-j, --jam-path <file>', 'the path to the jam executable').makeOptionMandatory();
const nodeprofOption = new Option('-n, --nodeprof-path <file>', 'the path to the NodeProf distribution with GraalVM').makeOptionMandatory();
const benchmarkNameOption = new Option('-b, --benchmark [names...]', "the name(s) of the benchmark to examine").choices(modulesToBenchmark);
const mainFileOption = new Option('-m, --main [file]', "the path to the benchmark's main/start file (relative to the benchmark's node module directory)")
const outputOption = new Option('-o, --out [file]', 'where to place the output file');

const fullEvaluationCmd = new Command('full-evaluation')
  .description('Run the full evaluation')
  .addOption(jamOption)
  .addOption(nodeprofOption)
  .addOption(benchmarkNameOption.default(modulesToBenchmark))
  .action((options) => {
    runFullEvaluation(path.resolve(options.jamPath), path.resolve(options.nodeprofPath), options.benchmark);
  });

const nodeprofTransformCmd = new Command('nodeprof-transform')
  .description('Transform the output of the dynamic (nodeprof) callgraph into something comparable')
  .requiredOption('-i, --input [file]', 'the raw callgraph file from nodeprof')
  .addOption(outputOption.makeOptionMandatory())
  .action((options) => {
    nodeProfTransformer(path.resolve(options.input), path.resolve(options.out));
  });

const runJamCmd = new Command('jam-cg')
  .description('generate the Jam CG for the benchmark')
  .addOption(jamOption)
  .addOption(nodeprofOption)
  .addOption(benchmarkNameOption.makeOptionMandatory())
  .addOption(mainFileOption)
  .addOption(outputOption.default(getJamCgsDir()))
  .action((options) => {
    const graalNodeHome = getGraalNodePath(path.resolve(options.nodeprofPath))
    const outputFile = path.resolve(options.out, options.benchmark);
    const moduleRootDir = getBenchmarkModulePath(options.benchmark);
    const startFile = options.main ? path.resolve(moduleRootDir, options.main) : getBenchmarkMainFile(options.benchmark);
    
    runJam(path.resolve(options.jamPath), graalNodeHome, options.benchmark, outputFile, startFile, moduleRootDir);
  });

const runDynamicCgCmd = new Command('dyn-cg')
  .description('generate the dynamic (nodeprof) CG for the benchmark')
  .addOption(jamOption)
  .addOption(nodeprofOption)
  .addOption(benchmarkNameOption.makeOptionMandatory())
  .addOption(mainFileOption)
  .addOption(outputOption.default(getDynCgsDir()))
  .action((options) => {
    const graalNodeHome = getGraalNodePath(path.resolve(options.nodeprofPath))
    const outputFile = path.resolve(options.out, options.benchmark);
    const moduleRootDir = getBenchmarkModulePath(options.benchmark);
    const startFile = options.main ? path.resolve(moduleRootDir, options.main) : getBenchmarkMainFile(options.benchmark);
    
    runDynCg(path.resolve(options.jamPath), graalNodeHome, options.benchmark, outputFile, startFile);
  });

program
  .addCommand(fullEvaluationCmd, { isDefault: true })
  .addCommand(nodeprofTransformCmd)
  .addCommand(runJamCmd)
  .addCommand(runDynamicCgCmd);

program.parse();