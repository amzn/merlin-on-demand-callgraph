import { execSync} from 'child_process';
import * as path from "path";
import { getBenchmarkMainFile, getBenchmarkModulePath } from './benchmark-utils';

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
  jamPath: string,
  graalNodeHome: string,
  benchmark: string,
  outputFile: string,
  mainFile: string = getBenchmarkMainFile(benchmark),
  moduleRootDir: string = getBenchmarkModulePath(benchmark)
) => {
  console.log(`Recording Jam CG for ${benchmark} into ${outputFile}`);
  const startFile = path.relative(moduleRootDir, mainFile)
  const jamCmd = `node ${jamPath}/dist/call-graph/index.js ${moduleRootDir} --client-main ${startFile} -o ${outputFile}`
  return execSync(jamCmd, {shell: '/bin/bash', env: { ...process.env, NODE_HOME: graalNodeHome }});
};