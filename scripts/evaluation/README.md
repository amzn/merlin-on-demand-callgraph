# Merlin Evaluation
This directory contains resources for empirically evaluating Merlin

## Prerequisites
- `node` and `npm`
  - tested with `node v18.0.0` and `npm 8.6.0`
- [Jam](https://github.com/cs-au-dk/ISSTA-2021-Paper-156), for comparison with Merlin
- [nodeprof.js](https://github.com/Haiyang-Sun/nodeprof.js), for collecting dynamic call graphs

## Setup
- Follow all installation and setup steps for [Jam](https://github.com/cs-au-dk/ISSTA-2021-Paper-156) and [nodeprof.js](https://github.com/Haiyang-Sun/nodeprof.js)
- run the `eval-setup.sh` script to install the evaluation dataset and fix a few minor path errors in Jam

## Running
The full evaluation can be run using `npm run full-eval -- -j <path-to-jam-dir> -n <path-to-nodeprof-dir>`. You can also pass an additional `-b <benchmark1> -b <benchmark2>` to run only on those benchmarks. `npm run full-eval -- --help` is also available.

TODO: additional instructions