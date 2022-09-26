#!/bin/bash

#j: PATH-TO-JAM (ISSTA2021 CG tool)
#p: PATH-TO-NODEPROF (used for generating dynamic call graphs)
while getopts 'j:n:' OPTION; do
  case "$OPTION" in
    j) PATH_TO_JAM="$OPTARG";;
    n) PATH_TO_NODEPROF="$OPTARG";;
    ?) echo "script usage: $(basename \$0) [-j path-to-jam] [-n path-to-nodeprof]" >&2
       exit 1;;
  esac
done

# TODO: the rest of the eval