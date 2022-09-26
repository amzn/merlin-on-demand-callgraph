#!/bin/bash

# Array of npm package names
declare -a PACKAGES=(
    "makeappicon"
    "toucht"
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

# top-level install
echo "Installing package.json dependencies via npm"
npm install --prefix eval-targets &>/dev/null

if [ ! -d "eval-targets/node_modules" ] ; then
  echo "Could not successfully install packages. Is npm properly installed?" && exit 1
fi

# package-level install
for PACKAGE in "${PACKAGES[@]}"
do
  echo "Installing ${PACKAGE} via npm"
  npm install --prefix "eval-targets/node_modules/${PACKAGE}" &>/dev/null
done

echo "Finished setup"
