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

package com.amazon.pvar.merlin.ir;

import dk.brics.tajs.flowgraph.jsnodes.Node;
import wpds.interfaces.Location;

/**
 * Abstract implementation of the SPDS Location interface.
 *
 * The Location interface is one part of an SPDS configuration, along with the State interface.  A transition between
 * two SPDS configurations is a Rule.
 *
 * In the Call-PDS, a Location is a program value, such as a Variable or Register, so the Variable and Register
 * wrapper classes extend this Value class.
 *
 * In the Field-PDS, a Location is a program value at a particular program point. To create a two-valued Location,
 * we use the Node<> interface provided by SPDS.
 * @see com.amazon.pvar.merlin.pdsgen.SPDSTransformNodeVisitor#generateFieldNormalRule(Node, Value, Node, Value)
 * for usage of the Node<> interface.
 */
public abstract class Value implements Location {
    /**
     * Implementation of a required Location method. It will not be used in this project, but "equals" is usually the
     * default implementation of this method in the SPDS framework.
     *
     * @param location
     * @return true if the locations are equal, false otherwise.
     */
    @Override
    public boolean accepts(Location location) {
        return this.equals(location);
    }
}
