#!/usr/bin/env node

const path = require('path');
const fs = require('fs');
const child_process = require('child_process');

const nodeModulesDir = path.resolve(path.resolve(__dirname, "node_modules"));

if (!fs.existsSync(nodeModulesDir)) {
  throw new Error(`Was expecting node_modules directory - ${nodeModulesDir} - to exist. 
  Did you forget to run 'npm install' in the eval-targets dir first?`);
}

const pkgDotJsonPath = path.resolve(__dirname, "package.json");
const pkgDotJsonContents = JSON.parse(fs.readFileSync(pkgDotJsonPath));

const modules = Object.keys(pkgDotJsonContents['dependencies']);
for (module of modules) {
  const modulePath = path.resolve(nodeModulesDir, module);
  child_process.execSync(`npm install --prefix ${modulePath}`);
}
