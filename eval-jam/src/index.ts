
import { Position } from "estree";
// Note: normally, since `jam` is declared as a dependency in the package.json we could just use `from "jam/src/..." here.
// However, the ISSTA-2021-Paper-156 does not declare it's main js file or typings correctly in its package.json and so
// the typescript compiler does not see the exported javascript thus while we use the types from node_modules/jam, we 
// have to point to the actual typescript code directly 
import { PropertyReadsOnLibraryObjectStrategies, ResolvedCallGraphNode, SimpleCallGraph } from "../../ISSTA-2021-Paper-156/src/usage-model-generator/compute-call-graph";
import { FunctionCreation } from "../../ISSTA-2021-Paper-156/src/usage-model-generator/access-path";
import { VulnerabilityScanner } from "../../ISSTA-2021-Paper-156/src/call-graph/scanner";
import * as fs from "fs";
import { resolve } from 'path';
import commander from 'commander';

type JsonCallGraphNode = {
  start: Position,
  end: Position,
  file: string
};

type JsonCallGraphEdge = {
  callee: JsonCallGraphNode,
  caller: JsonCallGraphNode
};

type JsonCallGraph = {
  edges: JsonCallGraphEdge[]
};

// By default positions in Jam's call graph use 1-based lines and 0-based columns. Since this
// is confusing, we instead convert them to use 1-based numbers for both lines and columns.
const sanitizePosition = (pos: Position): Position => {
  return {
    line: pos.line,
    column: pos.column + 1
  };
}

const callGraphToJSON = (cg: SimpleCallGraph): JsonCallGraph => {
  const result: JsonCallGraph = { edges: [] };
  for (const edge of cg.edges) {
    const targets = cg.edgeToTargets.get(edge);
    const sourceNode = edge.source.node;
    if (!(sourceNode instanceof FunctionCreation)) {
      continue;
    }
    if (!targets) {
      console.log(`WARN: could not get targets for ${edge}`);
    } else {
      const edgeSource = {
        start: sanitizePosition(edge.callSourceLocation.start),
        end: sanitizePosition(edge.callSourceLocation.end),
        file: resolve(sourceNode.file)
      };
      targets.forEach(target => {
        // We're only interested in successfully resolved call graph nodes.
        if (target instanceof ResolvedCallGraphNode && target.node instanceof FunctionCreation) {
          const edgeTarget = {
            start: sanitizePosition(target.node.sourceLocation.start),
            end: sanitizePosition(target.node.sourceLocation.end),
            file: resolve(target.node.file)
          };
          result.edges.push({
            caller: edgeSource,
            callee: edgeTarget
          });
        }
      });
    }
  }
  return result;
};

const writeJSONForCallGraph = (jsonCG: JsonCallGraph, jsonFile: fs.PathLike): void => {
  console.log(`Writing JSON call graph to ${jsonFile}`);
  fs.writeFileSync(jsonFile, JSON.stringify(jsonCG, undefined, 2));
};


commander
  .arguments('<client-folder>')
  .option(
    '--client-main [main file relative to client-folder]',
    'specify the file containing the program entry point (default resolved file is used otherwise)'
  )
  .option('-o, --out [file]', 'output the callgraph as dot to this file')
  .option('-d, --debug', 'Enable debug logging')
  .action(async (clientFolder: string, options: any): Promise<void> => {
    const mainFile = options.clientMain ? resolve(clientFolder, options.clientMain) : undefined;
    const scanner = new VulnerabilityScanner(clientFolder, mainFile, options.debug);
    await scanner.runScanner(true, PropertyReadsOnLibraryObjectStrategies.USE_FIELD_BASED_FROM_LIBRARY);
    const cg = scanner.getCallGraphFromMain();
    const jsonCG = callGraphToJSON(cg);
    const jsonString = JSON.stringify(jsonCG);
    console.log(jsonString);
    if (options.out) {
      writeJSONForCallGraph(jsonCG, options.out);
    }
  });
commander.parse();
