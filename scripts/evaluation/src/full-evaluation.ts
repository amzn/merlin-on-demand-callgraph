import * as fs from "fs-extra";
import * as path from "path";
import { nodeProfTransformer } from './nodeprof-transformer';
import { runDynCg, runJam, runMerlin, getGraalNodePath } from "./callgraphs";
import { getDynCgsDir, getJamCgsDir, getMerlinCgsDir, getBenchmarkMainFile } from "./benchmark-utils";

interface BenchmarkResult {
  name: string,
  outputFile: string
}

const generateCallgraph = (
  benchmarks: string[], 
  callgraphOutputDir: string, 
  outputFileSuffix: string, 
  callgraphFunc: (benchmark: string, outputPath: string) => Buffer
) => {
  fs.ensureDirSync(callgraphOutputDir);

  const generatedCallGraphs: BenchmarkResult[] = [];

  for (const benchmark of benchmarks) {
    try {
      const outputFile = path.resolve(callgraphOutputDir, `${benchmark}${outputFileSuffix}`);
      const resultBuffer = callgraphFunc(benchmark, outputFile);
      console.log(resultBuffer.toString());
      generatedCallGraphs.push({ name: benchmark, outputFile: outputFile});
    } catch (e) {
      // if there's an error continue with remaining benchmarks but log the problem
      console.error(e);
    }
  }

  return generatedCallGraphs;
};

const generateNodeProfCallGraphs = (jamPath: string, graalNodeHome: string, benchmarks: string[]): BenchmarkResult[] => {
  console.log("Generating Dynamic CGs");
  return generateCallgraph(benchmarks, getDynCgsDir(), ".raw", (benchmark: string, outputFile: string) => {
    const startFile = getBenchmarkMainFile(benchmark);
    return runDynCg(jamPath, graalNodeHome, benchmark, outputFile, startFile);
  });
};

const generateJamCallGraphs = (jamPath: string, graalNodeHome: string, benchmarks: string[]): BenchmarkResult[] => {
  console.log("Generating Jam CGs");
  return generateCallgraph(benchmarks, getJamCgsDir(), ".json", (benchmark: string, outputFile: string) => {
    return runJam(jamPath, graalNodeHome, benchmark, outputFile /* remaining defaults are okay */);
  });
};

const generateMerlinCallGraphs = (benchmarks: string[]): BenchmarkResult[] => {
  console.log("Generating Merlin CGs");
  return generateCallgraph(benchmarks, getMerlinCgsDir(), ".raw", (benchmark: string, outputFile: string) => {
    return runMerlin(benchmark, outputFile);
  });
};

const normalizeNodeProfOutput = (benchmark: BenchmarkResult): BenchmarkResult => {
  const outputFile = path.resolve(path.dirname(benchmark.outputFile), `${benchmark.name}.json`);
  console.log("Transforming output...");
  nodeProfTransformer(benchmark.outputFile, outputFile);
  console.log(`Final output written to ${outputFile}`);

  return {...benchmark, outputFile: outputFile};
};

const normalizeMerlinOutput = (benchmark: BenchmarkResult): BenchmarkResult => {
  const outputFile = path.resolve(path.dirname(benchmark.outputFile), `${benchmark.name}.json`);
  console.log("Transforming Merlin output...");

  const merlinContents = fs.readFileSync(benchmark.outputFile).toString()
  const merlinRawJson = JSON.parse(merlinContents);
  const callgraph = merlinRawJson['experimentResults'][0]['experimentCallGraph'];
  fs.writeFileSync(outputFile, JSON.stringify(callgraph, undefined, 2));

  console.log(`Final output written to ${outputFile}`);

  return {...benchmark, outputFile: outputFile};
};

export const runFullEvaluation = (jamPath: string, nodeProfPath: string, benchmarks: string[]) => {
  const graalNodeHome = getGraalNodePath(nodeProfPath);

  generateNodeProfCallGraphs(jamPath, graalNodeHome, benchmarks).map(normalizeNodeProfOutput);
  generateJamCallGraphs(jamPath, graalNodeHome, benchmarks);
  generateMerlinCallGraphs(benchmarks).map(normalizeMerlinOutput);
};