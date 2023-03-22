## Overview

This NPM package wraps JAM (ISSTA-2021-Paper-156/) and reformulates the output so that: 

1. Edges that are not interesting to our evaluation are removed

2. Edges are output in JSON format rather than .dot (graphviz) format


## Building 

1. Make sure that the JAM (ISSTA-2021-Paper-156) git submodule is checked out and you have run `npm install` in it

2. In this `eval-jam` directory, run `npm install && npm run build`

3. Check to make sure a `dist/` directory exists in this `eval-jam` directory

## Running

This is executed similar to JAM: 

`cd dist/eval-jam/src && node index.js /path/to/node/module/to/analyze --client-main path/to/start/file --out ./path/to/the/json/output/file`

Note that you must be in the `dist/eval-jam/src` directory, otherwise the relative path to the jam resources won't work. 
This is due to a bug in JAM's package.json where it doesn't correctly export it's main files and resources.

## Notes:

* You can also pass `--help` and --debug` to the script

* This is configured in our `package.json`, but eval-jam needs to specify the exact version of `logform` (2.4.2) otherwise the types exported
by JAM won't work. This is due to the logform package moving on since development ceased on the paper repository. 

