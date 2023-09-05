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

package com.amazon.pvar.merlin.solver

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

// Various statistics to aid debugging
object HandlerStats {
  var handlerRegistrationMap: mutable.Map[String, Long] = mutable.Map.empty

  def logHandlerRegistration(tag: String): Unit = handlerRegistrationMap.synchronized {
    handlerRegistrationMap.get(tag) match {
      case Some(value) =>
        handlerRegistrationMap(tag) = value + 1
      case None => handlerRegistrationMap(tag) = 1
    }
  }

  def status: String = {
    val registrationMapStatus = handlerRegistrationMap.toList.sortBy(_._2).reverse
    registrationMapStatus.toString
  }

  def reset(): Unit = {
    handlerRegistrationMap = mutable.Map.empty
  }
}
