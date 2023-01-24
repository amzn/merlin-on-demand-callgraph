#!/usr/bin/env bash

usage() {
    echo "script usage: $(basename $0) -j path-to-jam -n path-to-nodeprof" >&2
}

#j: PATH-TO-JAM (ISSTA2021 CG tool)
while getopts 'j:n:' OPTION; do
  case "$OPTION" in
    j) PATH_TO_JAM="$OPTARG";;
    n) PATH_TO_NODEPROF="$OPTARG";;
    ?) echo "script usage: $(basename \$0) -j path-to-jam" >&2
       exit 1;;
  esac
done

if [ -z "$PATH_TO_JAM" ] ; then
  echo "Error: No -j option provided"
  usage
  exit 1
fi

if [ -z "$PATH_TO_NODEPROF" ] ; then
  echo "Error: No -n option provided"
  usage
  exit 1
fi

# Associative array of benchmarks to their respective main files, using evaluation directory as base for the relative path
declare -A BENCHMARKS=(
  ["makeappicon"]="eval-targets/node_modules/makeappicon/bin/index.js"
  ["toucht"]="eval-targets/node_modules/toucht/bin/toucht"
  ["spotify-terminal"]="eval-targets/node_modules/spotify-terminal/bin/music"
  ["ragan-module"]="eval-targets/node_modules/ragan-module/bin/greeter.js"
  ["npm-git-snapshot"]="eval-targets/node_modules/npm-git-snapshot/bin/npm-git-snapshot"
  ["nodetree"]="eval-targets/node_modules/nodetree/index.js"
  ["jwtnoneify"]="eval-targets/node_modules/jwtnoneify/index.js"
  ["foxx-framework"]="eval-targets/node_modules/foxx-framework/bin/foxxy"
  ["npmgenerate"]="eval-targets/node_modules/npmgenerate/bin/ngen.js"
  ["smrti"]="eval-targets/node_modules/smrti/app.js"
  ["openbadges-issuer"]="eval-targets/node_modules/openbadges-issuer/cli.js"
)

# Associative array of benchmarks to their respective main files, using the benchmark directory as base for the relative path
declare -A BENCHMARKS_JAM=(
  ["makeappicon"]="bin/index.js"
  ["toucht"]="bin/toucht"
  ["spotify-terminal"]="bin/music"
  ["ragan-module"]="bin/greeter.js"
  ["npm-git-snapshot"]="bin/npm-git-snapshot"
  ["nodetree"]="index.js"
  ["jwtnoneify"]="index.js"
  ["foxx-framework"]="bin/foxxy"
  ["npmgenerate"]="bin/ngen.js"
  ["smrti"]="app.js"
  ["openbadges-issuer"]="cli.js"
)

# Jam expects an environment variable NODE_HOME pointing to GraalVM's node distribution
export NODE_HOME="${PATH_TO_NODEPROF}/graal/sdk/latest_graalvm_home"

# Generate the dynamic CGs
if [ ! -d "dyn_cgs" ] ; then
  mkdir "dyn_cgs"
fi

echo "Generating Dynamic CGs"
CURRENT_DIR=$(pwd)
for BENCHMARK in "${!BENCHMARKS[@]}"; do
  bash dyn-cg.sh -j "${PATH_TO_JAM}" -b "${BENCHMARKS[$BENCHMARK]}" -n "${BENCHMARK}.raw"

  # reformat the dynamic CGs so that it's comparable to the static ones
  cd nodeprof-transformer
  npm run makeComparable -- --input ../dyn_cgs/"${BENCHMARK}.raw" --out ../dyn_cgs/"${BENCHMARK}.json"
  cd "$CURRENT_DIR"  
done

# Generate Jam's CGs
if [ ! -d "jam_cgs" ] ; then
  mkdir "jam_cgs"
fi

echo "Generating Jam's Static CGs"
for BENCHMARK in "${!BENCHMARKS_JAM[@]}"; do
  bash jam-cg.sh -j "${PATH_TO_JAM}" -b "${BENCHMARKS_JAM[$BENCHMARK]}" -n "${BENCHMARK}"
done

# TODO: the rest of the eval
