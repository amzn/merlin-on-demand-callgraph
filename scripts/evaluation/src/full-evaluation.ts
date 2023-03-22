import * as fs from "fs-extra";
import * as path from "path";
import { nodeProfTransformer } from './nodeprof-transformer';
import { runDynCg, runJam, getGraalNodePath } from "./callgraphs";
import { getDynCgsDir, getJamCgsDir, getBenchmarkMainFile } from "./benchmark-utils";

interface BenchmarkResult {
  name: string,
  outputFile: string
}

const normalizeNodeProfOutput = (benchmark: BenchmarkResult): BenchmarkResult => {
  const outputFile = path.resolve(path.dirname(benchmark.outputFile), `${benchmark.name}.json`);
  console.log("Transforming output...");
  nodeProfTransformer(benchmark.outputFile, outputFile);
  console.log(`Final output written to ${outputFile}`);

  return {...benchmark, outputFile: outputFile};
}

const generateNodeProfCallGraphs = (jamPath: string, graalNodeHome: string, benchmarks: string[]): BenchmarkResult[] => {
  console.log("Generating Dynamic CGs");
  const dynCallgraphOutDir = getDynCgsDir();
  const generatedCallGraphs: BenchmarkResult[] = [];
  fs.ensureDirSync(dynCallgraphOutDir);

  for (const benchmark of benchmarks) {

    const startFile = getBenchmarkMainFile(benchmark);
    const rawOutputFilePath = path.resolve(dynCallgraphOutDir, `${benchmark}.raw`);

    try {
      const resultBuffer = runDynCg(jamPath, graalNodeHome, benchmark, rawOutputFilePath, startFile);
      console.log(resultBuffer.toString());
      generatedCallGraphs.push({ name: benchmark, outputFile: rawOutputFilePath});
      
    } catch (e) {
      // if there's an error continue with remaining benchmarks but log the problem
      console.error(e);
    }
  }

  return generatedCallGraphs;
};

const generateJamCallGraphs = (jamPath: string, graalNodeHome: string, benchmarks: string[]): BenchmarkResult[] => {
  const jamCallgraphOutDir = getJamCgsDir();
  fs.ensureDirSync(jamCallgraphOutDir);

  const generatedCallGraphs: BenchmarkResult[] = [];

  for (const benchmark of benchmarks) {
    try {
      const outputFile = path.resolve(jamCallgraphOutDir, `${benchmark}.dot`);
      const resultBuffer = runJam(jamPath, graalNodeHome, benchmark, outputFile /* remaining defaults are okay */);
      console.log(resultBuffer.toString());
      generatedCallGraphs.push({ name: benchmark, outputFile: outputFile});
    } catch (e) {
      // if there's an error continue with remaining benchmarks but log the problem
      console.error(e);
    }
  }

  return generatedCallGraphs;
}

export const runFullEvaluation = (jamPath: string, nodeProfPath: string, benchmarks: string[]) => {
  const graalNodeHome = getGraalNodePath(nodeProfPath);

  generateNodeProfCallGraphs(jamPath, graalNodeHome, benchmarks).map(normalizeNodeProfOutput);
  generateJamCallGraphs(jamPath, graalNodeHome, benchmarks);
};