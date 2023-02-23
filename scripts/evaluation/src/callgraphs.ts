import { execSync} from 'child_process';
import { existsSync } from 'fs-extra';
import * as path from "path";
import { getBenchmarkMainFile, getBenchmarkModulePath, projectRootDir } from './benchmark-utils';

// Jam expects an environment variable NODE_HOME pointing to GraalVM's node distribution
export const getGraalNodePath = (nodeProfDir: string) => path.resolve(nodeProfDir, "graal", "sdk", "latest_graalvm_home");

export const runDynCg = (
  jamPath: string,
  graalNodeHome: string,
  benchmark: string,
  outputFile: string,
  mainFile: string = getBenchmarkMainFile(benchmark)
) => {
  console.log(`Recording dynamic CG for ${benchmark} into ${outputFile}`);
  const dynCgCmd = `node ${jamPath}/dist/node-prof-analyses/index.js call-graph ${mainFile} ${outputFile}`
  return execSync(dynCgCmd, {shell: '/bin/bash', env: { ...process.env, NODE_HOME: graalNodeHome }});
}

export const runJam = (
  jamPath: string, // temporarily not used while eval-jam/ script is not incorporated into this library
  graalNodeHome: string,
  benchmark: string,
  outputFile: string,
  mainFile: string = getBenchmarkMainFile(benchmark),
  moduleRootDir: string = getBenchmarkModulePath(benchmark)
) => {
  console.log(`Recording Jam CG for ${benchmark} into ${outputFile}`);
  // Note: this was/will go back to ${jamPath}/dist/call-graph/index.js
  const evalJamScript = path.resolve(projectRootDir, "eval-jam", "dist", "eval-jam", "src", "index.js");
  if (!existsSync(evalJamScript)) {
    throw new Error(`File ${evalJamScript} does not exist! Did you run 'npm install && npm run build' in the eval-jam directory?`);
  }
  const startFile = path.relative(moduleRootDir, mainFile)
  const jamCmd=`node ${evalJamScript} ${moduleRootDir} --client-main ${startFile} -o ${outputFile}`
  
  return execSync(jamCmd, {shell: '/bin/bash', env: { ...process.env, NODE_HOME: graalNodeHome }});
};