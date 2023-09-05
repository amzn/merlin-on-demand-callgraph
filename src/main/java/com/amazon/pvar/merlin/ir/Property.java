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

import wpds.interfaces.Empty;
import wpds.wildcard.Wildcard;

import java.util.Objects;

/**
 * A representation of object properties that is compatible with the SPDS framework
 */
public class Property extends Value {

    private final String propertyName;

    public Property(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public static WildcardProperty getWildcard() {
        return WildcardProperty.instance;
    }

    public static EpsilonProperty getEpsilon() {
        return EpsilonProperty.instance;
    }

    public static EmptyProperty getEmpty() {
        return EmptyProperty.instance;
    }

    @Override
    public String toString() {
        return "[\"" + propertyName + "\"]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Property property = (Property) o;
        return Objects.equals(propertyName, property.propertyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyName);
    }

    /**
     * The "wildcard" property, which signifies the transitive closure of the "has property" relation on an object.
     *
     * For all singleton IR classes (like this one), it is important to make sure that the correct SPDS interface
     * is implemented. The SPDS solver performs instanceof checks against the following interfaces:
     *  - Empty
     *  - Wildcard
     *  - ExclusionWildcard
     * If the correct interface is not implemented on the IR classes passed to the SPDS solver, the analysis will not
     * propagate date flow correctly, and the issue will likely be very difficult to debug.
     */
    private static class WildcardProperty extends Property implements Wildcard {

        private static final WildcardProperty instance = new WildcardProperty();

        private WildcardProperty() {
            super("*");
        }

    }

    /**
     * SPDS uses "epsilon" stack symbols to represent transitions in the saturated P-Automaton that are taken without
     * any input symbol.
     *
     * For all singleton IR classes (like this one), it is important to make sure that the correct SPDS interface
     * is implemented. The SPDS solver performs instanceof checks against the following interfaces:
     *  - Empty
     *  - Wildcard
     *  - ExclusionWildcard
     * If the correct interface is not implemented on the IR classes passed to the SPDS solver, the analysis will not
     * propagate date flow correctly, and the issue will likely be very difficult to debug.
     */
    private static class EpsilonProperty extends Property implements Empty {

        private static final EpsilonProperty instance = new EpsilonProperty();

        private EpsilonProperty() {
            super("EpsilonProperty");
        }
    }

    /**
     * SPDS uses "empty" stack symbols to represent transitions from one state in the saturated automaton to itself.
     * The concept of a "self-transition" does not arise in the formalization of SPDS, but it is used in the
     * implementation
     *
     * For all singleton IR classes (like this one), it is important to make sure that the correct SPDS interface
     * is implemented. The SPDS solver performs instanceof checks against the following interfaces:
     *  - Empty
     *  - Wildcard
     *  - ExclusionWildcard
     * If the correct interface is not implemented on the IR classes passed to the SPDS solver, the analysis will not
     * propagate date flow correctly, and the issue will likely be very difficult to debug.
     */
    private static class EmptyProperty extends Property implements Empty {

        private static final EmptyProperty instance = new EmptyProperty();

        private EmptyProperty() {
            super("EmptyProperty");
        }
    }
}
