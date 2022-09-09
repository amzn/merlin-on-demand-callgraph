/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.pvar.tspoc.merlin.solver.flowfunctions;

import com.amazon.pvar.tspoc.merlin.ir.Variable;
import dk.brics.tajs.flowgraph.Function;
import dk.brics.tajs.flowgraph.jsnodes.DeclareFunctionNode;
import dk.brics.tajs.flowgraph.jsnodes.ReadVariableNode;
import dk.brics.tajs.flowgraph.jsnodes.WriteVariableNode;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for determining whether a particular variable could be referenced in nested inner scopes of a function
 */
public class SyntacticReferenceResolver {

    private final Function outerFunction;
    private final Variable variable;

    public SyntacticReferenceResolver(Function outerFunction, Variable variable) {
        this.outerFunction = outerFunction;
        this.variable = variable;
    }

    /**
     * Return true if the variable is referenced by one of the nested inner functions of outerFunction, false otherwise
     * @return
     */
    public boolean isVarReferencedInNestedScopes() {
        Queue<Function> worklist = new LinkedList<>();
        worklist.add(outerFunction);
        while (!worklist.isEmpty()) {
            Function current = worklist.remove();

            // Is it possible for var to be used in this function?
            if (!variable.isVisibleIn(current)) {
                continue;
            }

            // Is var used in this function?
            Set<ReadVariableNode> varReadNodes = current.getBlocks().stream()
                    .flatMap(bb -> bb.getNodes().stream())
                    .filter(node -> node instanceof ReadVariableNode)
                    .map(node -> ((ReadVariableNode) node))
                    .collect(Collectors.toSet());
            for (ReadVariableNode readNode : varReadNodes) {
                if (readNode.getVariableName().equals(variable.getVarName())) {
                    return true;
                }
            }
            Set<WriteVariableNode> varWriteNodes = current.getBlocks().stream()
                    .flatMap(bb -> bb.getNodes().stream())
                    .filter(node -> node instanceof WriteVariableNode)
                    .map(node -> ((WriteVariableNode) node))
                    .collect(Collectors.toSet());
            for (WriteVariableNode writeNode : varWriteNodes) {
                if (writeNode.getVariableName().equals(variable.getVarName())) {
                    return true;
                }
            }

            // does this function have inner functions which might reference var?
            current.getBlocks().stream()
                    .flatMap(bb -> bb.getNodes().stream())
                    .filter(node -> node instanceof DeclareFunctionNode)
                    .forEach(node -> worklist.add(((DeclareFunctionNode) node).getFunction()));
        }
        return false;
    }
}
