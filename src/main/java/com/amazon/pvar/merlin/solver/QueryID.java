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

/** Used to refer to queries when registering handlers on `LiveSet`s. The
  * following tuple uniquely identifies a sub query:
  *   - Initial query, including direction
  *   - Sub-query, including direction
  *   - Whether the query was launched from an unbalanced pop listener, which
  *     can result in identical subqueries to those issued by other flow
  *     functions.
  */
public interface QueryID {}
