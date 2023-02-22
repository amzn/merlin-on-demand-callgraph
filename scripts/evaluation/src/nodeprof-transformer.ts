
import * as fs from "fs-extra";

interface ZeroBasedLocation {
  "line": number
  "column": number
};

interface OneBasedLocation {
  "line": number
  "column": number
};

type Location = ZeroBasedLocation | OneBasedLocation; 

interface SourceLocation {
  "start": Location
  "end": Location
  "source": string
}

interface NodeProfCallGraphEntry {
  "from": SourceLocation
  "to" : SourceLocation
  "isLoad" : boolean
}

const transformLocationToOneBased = (orig: ZeroBasedLocation): OneBasedLocation => ({
  "line": orig.line + 1,
  "column" : orig.column + 1
})

const transformSLToOneBased = (orig: SourceLocation): SourceLocation => ({
  ...orig,
  "start" : transformLocationToOneBased(orig.start as ZeroBasedLocation),
  "end" : transformLocationToOneBased(orig.end as ZeroBasedLocation)
})

const transformCGEntryToOneBased = (orig: NodeProfCallGraphEntry): NodeProfCallGraphEntry => ({
  ...orig,
  "from" : transformSLToOneBased(orig.from),
  "to" : transformSLToOneBased(orig.to)
})


export const nodeProfTransformer = (inputFilePath: string, outputFilePath: string) => {

  // read in file passed in
  const inputFile = fs.readFileSync(inputFilePath);
  const nodeprofJSONArray: NodeProfCallGraphEntry[] = JSON.parse(inputFile.toString()) as NodeProfCallGraphEntry[];

  // reformat
  const filteredNodeprofJSON = nodeprofJSONArray.filter(e => !e["isLoad"])// .map(transformCGEntryToOneBased)

  // write 
  fs.writeFileSync(outputFilePath, JSON.stringify(filteredNodeprofJSON, undefined, 2));
}
