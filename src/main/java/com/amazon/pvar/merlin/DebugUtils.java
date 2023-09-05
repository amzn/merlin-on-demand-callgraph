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

package com.amazon.pvar.merlin;

import org.apache.log4j.Logger;

import java.util.Arrays;

public class DebugUtils {
    final private static Logger logger = org.apache.log4j.Logger.getRootLogger();

    public static void debug(final String str) {
        logger.debug(str);
    }

    public static void warn(final String str) {
        logger.warn(str);
    }

    public static boolean isInvocationFound() {
        final var stackTrace = Thread.currentThread().getStackTrace();
        return Arrays.stream(stackTrace)
                .filter(elem -> elem.toString().contains("CallGraph.java:135"))
                .findFirst()
                .isPresent();
    }
}
