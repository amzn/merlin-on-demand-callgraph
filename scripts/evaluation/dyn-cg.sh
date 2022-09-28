#!/usr/bin/env bash

usage() {
    echo "script usage: $(basename $0) -j path-to-jam -b path-to-benchmark-main -n benchmark-name" >&2
}

#j: PATH-TO-JAM (ISSTA2021 CG tool)
#b: PATH-TO-BENCHMARK-MAIN
#n: BENCHMARK-NAME
while getopts 'j:b:n:' OPTION; do
  case "$OPTION" in
    j) PATH_TO_JAM="$OPTARG";;
    b) PATH_TO_BENCHMARK_MAIN="$OPTARG";;
    n) BENCHMARK_NAME="$OPTARG";;
    ?) usage
       exit 1;;
  esac
done

if [ -z "$PATH_TO_JAM" ] ; then
  echo "Error: No -j option provided"
  usage
  exit 1
fi

if [ -z "$PATH_TO_BENCHMARK_MAIN" ] ; then
  echo "Error: No -b option provided"
  usage
  exit 1
fi

if [ -z "$BENCHMARK_NAME" ] ; then
  echo "Error: No -n option provided"
  usage
  exit 1
fi

echo "Recording dynamic CG for: ${BENCHMARK_NAME}"
node "${PATH_TO_JAM}"/dist/node-prof-analyses/index.js call-graph "${PATH_TO_BENCHMARK_MAIN}" dyn_cgs/"${BENCHMARK_NAME}"