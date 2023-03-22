
import * as path from "path";

const defaultEvaluationDir = path.resolve(__dirname, '..');

export const getDynCgsDir = (rootDir = defaultEvaluationDir) => path.resolve(rootDir, "dyn_cgs");
export const getJamCgsDir = (rootDir = defaultEvaluationDir) => path.resolve(rootDir, "jam_cgs");
export const getBenchmarkModulesDir  = (rootDir = defaultEvaluationDir) =>  path.resolve(rootDir, "eval-targets", "node_modules")

// map benchmark modules to their respective main files, using the benchmark modules's root directory as base for the relative path
const mainFilesForBenchmarks: Record<string, string> = {
  "makeappicon" : "bin/index.js",
  "toucht" : "bin/toucht",
  "spotify-terminal" : "bin/music",
  "ragan-module" : "bin/greeter.js",
  "npm-git-snapshot" : "bin/npm-git-snapshot",
  "nodetree" : "index.js",
  "jwtnoneify" : "index.js",
  "foxx-framework" : "bin/foxxy",
  "npmgenerate" : "bin/ngen.js",
  "smrti" : "app.js",
  "openbadges-issuer" : "cli.js",
};

export const modulesToBenchmark = Object.keys(mainFilesForBenchmarks);

export const getBenchmarkModulePath = (benchmark: string) => path.resolve(getBenchmarkModulesDir(), benchmark);

export const getBenchmarkMainFile = (benchmark: string) => {
  return path.resolve(getBenchmarkModulePath(benchmark), mainFilesForBenchmarks[benchmark]);
}