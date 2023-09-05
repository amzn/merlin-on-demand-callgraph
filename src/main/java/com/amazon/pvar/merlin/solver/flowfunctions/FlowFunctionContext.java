/*
 * Copyright 2022-2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.pvar.merlin.solver.flowfunctions;

import com.amazon.pvar.merlin.ir.NodeState;
import com.amazon.pvar.merlin.ir.Value;
import sync.pds.solver.nodes.Node;

public record FlowFunctionContext(Node<NodeState, Value> currentPDSNode) {
    public Value queryValue() {
        return currentPDSNode.fact();
    }
}
