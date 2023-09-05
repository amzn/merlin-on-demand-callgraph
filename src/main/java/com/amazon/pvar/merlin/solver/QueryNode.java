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

package com.amazon.pvar.merlin.solver;

import java.util.HashSet;
import java.util.Set;

/** Tracks various metadata about queries, such as whether answering it produced errors. */
public class QueryNode {
    private final Query query;
    private final Set<Exception> errors = new HashSet<>();

    public QueryNode(Query query) {
        this.query = query;
    }

    public void registerError(Exception error) {
        this.errors.add(error);
    }

    public Set<Exception> getErrors() {
        return errors;
    }

    public Query getQuery() {
        return this.query;
    }

    public String toVertexLabel() {
        return query.toString();
    }
}
