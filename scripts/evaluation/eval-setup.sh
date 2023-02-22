#!/usr/bin/env bash

CURRENT_DIR=$(pwd)

# Array of npm package names
declare -a PACKAGES=(
    "makeappicon"
    "spotify-terminal"
    "ragan-module"
    "npm-git-snapshot"
    "nodetree"
    "jwtnoneify"
    "foxx-framework"
    "npmgenerate"
    "smrti"
    "openbadges-issuer"
)

# Associative array of benchmarks to their respective main files, using the benchmark directory as base for the relative path
declare -A BENCHMARKS=(
  ["makeappicon"]="lib/index.js" # Rollup works
  ["spotify-terminal"]="src/control.js"
  ["ragan-module"]="index.js"
  ["npm-git-snapshot"]="index.js"
  ["nodetree"]="index.js" # Rollup works
  ["jwtnoneify"]="src/noneify.js"
  ["foxx-framework"]="bin/foxxy"
  ["npmgenerate"]="bin/ngen.js"
  ["smrti"]="app.js" # Rollup works
  ["openbadges-issuer"]="cli.js"
)

usage() {
    echo "script usage: $(basename $0) -j path-to-jam" >&2
}

#j: PATH-TO-JAM (ISSTA2021 CG tool)
while getopts 'j:' OPTION; do
  case "$OPTION" in
    j) PATH_TO_JAM="$OPTARG";;
    ?) usage
       exit 1;;
  esac
done

if [ -z "$PATH_TO_JAM" ] ; then
  echo "Error: No -j option provided"
  usage
  exit 1
fi

# top-level install
echo "Installing package.json dependencies via npm"
npm install --prefix eval-targets &>/dev/null

if [ ! -d "eval-targets/node_modules" ] ; then
  echo "Could not successfully install packages. Is npm properly installed?" && exit 1
fi

# install rollup
echo "Installing rollup for bundling of benchmark modules"
npm install -g rollup &>/dev/null
echo "Installing rollup plugins"
npm install -g @rollup/plugin-node-resolve &>/dev/null
npm install -g @rollup/plugin-json &>/dev/null
npm install -g @rollup/plugin-commonjs &>/dev/null

# package-level install
for PACKAGE in "${PACKAGES[@]}"
do
  echo "Installing ${PACKAGE} via npm"
  npm install --prefix "eval-targets/node_modules/${PACKAGE}" &>/dev/null
  echo "bundling ${PACKAGE} with rollup"
  cd "eval-targets/node_modules/${PACKAGE}"
  # outputs a bundled file to eval-targets/node_modules/${PACKAGE}/bundle.js
  rollup "${BENCHMARKS[$PACKAGE]}" --file bundle.js --format cjs -p node-resolve -p commonjs -p json
  cd "${CURRENT_DIR}"
done

# Fix path errors in Jam's static-configuration.ts file
echo "Fixing Jam path errors"
PROJ_HOME_OLD="isInTest ? '../' : '../../'"
PROJ_HOME_NEW="'../'"
NODE_PROF_OLD="'src', 'node-prof-analyses'"
NODE_PROF_NEW="'dist', 'node-prof-analyses'"
sed -i '' "s+$NODE_PROF_OLD+$NODE_PROF_NEW+g" "${PATH_TO_JAM}"/src/static-configuration.ts
sed -i '' "s+$PROJ_HOME_OLD+$PROJ_HOME_NEW+g" "${PATH_TO_JAM}"/src/static-configuration.ts
cd "$PATH_TO_JAM"
echo "re-compiling Jam"
npm run build

echo "Installing node-based evaluation tool"
npm install --prefix "${CURRENT_DIR}" &>/dev/null

cd "$CURRENT_DIR"
echo "Finished setup"
