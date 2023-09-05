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

package com.amazon.pvar.merlin.livecollections

import com.amazon.pvar.merlin.experiments.CollectEvaluationData
import com.amazon.pvar.merlin.solver.HandlerStats

import java.util
import java.util.{Arrays, Optional}
import java.util.concurrent.{ForkJoinPool, RejectedExecutionException, TimeUnit}
import scala.jdk.CollectionConverters._

/** Mostly a wrapper around a thread pool to keep track of all threads involved
  * in a computation using `LiveSet`s.
  */
final class Scheduler(
    pool: ForkJoinPool = new ForkJoinPool(Scheduler.threadCount)
) {

  def waitUntilDone(): Unit = {
    while (!pool.awaitQuiescence(1, TimeUnit.HOURS)) {}
  }

  def addThread(func: => Unit): Unit = {
    if (CollectEvaluationData.debugFlag) {
      // Collect statistics to identify where most of the threads are spawned
      val stackTrace = Thread.currentThread.getStackTrace
      val direction = stackTrace.collectFirst({
        case event if event.toString.contains("BackwardFlowFunctions") => "bwd"
        case event if event.toString.contains("ForwardFlowFunctions") => "fwd"
        case event if event.toString.contains("ForwardMerlinSolver") => "fwd"
        case event if event.toString.contains("BackwardMerlinSolver") => "bwd"
      }).getOrElse("unk")
      val handlerTag = stackTrace.collectFirst({
        case event if event.toString.contains("BackwardFlowFunctions.handleflowToFunctionEntry") =>
          "backwardEntry"
        case event if event.toString.contains("resolveFunctionCall") =>
          s"resolveFunctionCall:$direction"
        case event if event.toString.contains("handleFlowToReturn") =>
          s"flowToReturn:$direction"
        case event if event.toString.contains("CallGraph.java:135") =>
          s"invocationFound:$direction"
        case event if event.toString.contains("CallGraph.java:136") =>
          s"calleeFound:$direction"
        case event if event.toString.contains("findInvocationsOfFunction") =>
          s"findInvocationSolverStart:$direction"
        case event if event.toString.contains("solveQueries") =>
          "initial"
        case event if event.toString.contains("registerCalleeHandler") =>
          s"calleeFound:$direction"
        case event if event.toString.contains("registerInvocationFoundHandler") =>
          s"invocationFound:$direction"
        case event if event.toString.contains("withAllocationSitesOf") =>
          s"aliasFound:$direction"
        case event if event.toString.contains("addPointsToFact") =>
          s"aliasFound:$direction"
      })
      handlerTag.foreach(HandlerStats.logHandlerRegistration)
      if (handlerTag.isEmpty) {
        HandlerStats.logHandlerRegistration("other")
      }
    }
    try {
      pool.execute(() => func)
    } catch {
      case rej: RejectedExecutionException => // timeout reached
    }
  }

  def status(): String = {
    s"queued tasks: ${pool.getQueuedTaskCount}; active: ${pool.getActiveThreadCount}"
  }

  def cancel(): Unit = {
    pool.shutdownNow()
  }

}

object Scheduler {
  val threadCount = Runtime.getRuntime.availableProcessors()
//  val threadCount = 1
  // Java-friendly constructors (since Java does not support default parameters)
  def create(): Scheduler = new Scheduler(new ForkJoinPool(threadCount))

  def create(pool: ForkJoinPool) = new Scheduler(pool)
}
