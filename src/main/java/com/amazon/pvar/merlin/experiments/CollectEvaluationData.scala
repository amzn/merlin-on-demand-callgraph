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

package com.amazon.pvar.merlin.experiments

import com.amazon.pvar.merlin.ir.FlowgraphUtils
import com.amazon.pvar.merlin.livecollections.Scheduler
import com.amazon.pvar.merlin.solver.{HandlerStats, QueryManager}
import com.amazon.pvar.merlin.solver.flowfunctions.AbstractFlowFunctions
import QueryManager.BackwardQuery
import dk.brics.tajs.flowgraph.{FlowGraph, SourceLocation}

import java.util.Optional
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.syntax._

import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ForkJoinPool
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random
import scala.util.DynamicVariable

final case class ExperimentResult(
    runningTimeInMillis: Long = -1, // uninitialized when produced by experiments.Main before parsing output of /bin/time
    memoryUsageInBytes: Long = -1,
    cpuTimeInMillis: Long = -1,
    numberOfRequestedQueries: Long,
    numberOfAllQueries: Long,
    numberOfCallsResolutionQueries: Long,
    numberOfPropertyAccessQueries: Long,
    totalNumberOfCallNodes: Long,
    totalNumberOfPropertyAccesses: Long,
    timeoutInMillis: Long,
    queryIndices: Set[Int],
    exitCode: Int = 0,
    benchmarkName: String = "unknown",
    isWholeProgram: Boolean = false,
    callEdgesFound: Int = -1,
    linesInFile: Int = -1,
    fullFilePath: String = "unknown",
    threadCount: Int = -1,
    tajsNodeIndices: Seq[Int] = Seq.empty[Int],
    iterations: Int = -1
)


object CollectEvaluationData extends App {
  val debugFlag = false
  val timeout = 5.minutes
  var threadCount = new DynamicVariable(Runtime.getRuntime.availableProcessors())
  runOnSyntheticBenchmarks(os.pwd / "benchmarks", resultsDir / "synth")
  threadCount.withValue(1)(
    runOnSyntheticBenchmarks(os.pwd / "benchmarks", resultsDir / "synthSingle")
  )

  private def runOnSyntheticBenchmarks(benchDir: os.Path, dataDir: os.Path): Unit = {
    val synthBenchmarks = os.walk(benchDir)
      .filter(_.ext.endsWith("js"))
    val random = new Random(42)
    val batches = Seq((1, 10), (5, 10), (10, 5), (50, 2), (100, 2), (250, 2), (500, 2), (1000, 1), (-1, 1))
    synthBenchmarks.flatMap(synthBenchmark => {
      val reportFile = ensureDirExists(dataDir) / (synthBenchmark.baseName + ".json")
      runRandomQueryBatches(synthBenchmark.toString, batches, random, reportFile)
    })
  }

  /** batchSizesAndCounts contains list of (batchSize, numberOfBatchesOfThatSize) tuples */
  private def runRandomQueryBatches(jsFile: String, batchSizesAndCounts: Seq[(Int, Int)], random: Random, reportFile: os.Path): Seq[ExperimentResult] = {
    val flowGraph = Main.flowgraphWithoutBabel(jsFile, debugFlag)
    val callSites = FlowgraphUtils.allCallNodes(flowGraph)
      // Filter out internal TAJS function calls
      .filter(call => call.getTajsFunctionName == null)
      .filter(call => call.getSourceLocation.getKind != SourceLocation.Kind.SYNTHETIC)
      .toList
      .asScala
    val possibleCallSiteQueries = callSites.flatMap(
      callSite => AbstractFlowFunctions.queriesToResolveFunctionCall(callSite).asScala.map(_.queryValue)
    ).zipWithIndex.toSeq
    val batches = for {
      batchSizeAndCount <- batchSizesAndCounts
      (batchSize, numberBatches) = batchSizeAndCount
      shuffled = random.shuffle(possibleCallSiteQueries)
      batchesOfThisSize <- shuffled.grouped(if (batchSize >= 0) batchSize else possibleCallSiteQueries.size).take(numberBatches).toSeq
    } yield batchesOfThisSize
    batches.map(batch => {
      val (batchSize, isWholeProg) = if (batch.size < 0) {
        (possibleCallSiteQueries.size, true)
      } else {
        (batch.size, false)
      }
      val batchStr = batchSize + "." + batch.map(_._2).hashCode().toHexString
      val batchResultFile = os.Path(reportFile.toString.stripSuffix(reportFile.ext) + batchStr + ".json")
      println(s"BATCH SIZE: ${batch.size}")
      val batchResults = runExperiment(os.Path(new File(jsFile).getAbsolutePath), flowGraph, batch, batchResultFile, isWholeProgram = isWholeProg).copy(isWholeProgram = isWholeProg)
      batchResults
    })
  }


  def runExperiment(inputFile: os.Path,
    flowGraph: FlowGraph,
    backwardQueries: Iterable[(BackwardQuery, Int)],
    resultsFile: os.Path,
    isWholeProgram: Boolean = false
  ): ExperimentResult = {
    println(s"${Instant.now}: $inputFile -> $resultsFile")
    val linesInFile = os.read(inputFile).linesIterator.size
    HandlerStats.reset()
    FlowgraphUtils.clearCaches()
    FlowgraphUtils.currentFlowGraph = flowGraph
    val nodeIndices = backwardQueries.map({
      case (query, _) => query.stmt().getNode.getIndex
    }).toSeq
    val tc = threadCount.value
    val queryManager = new QueryManager(flowGraph, new Scheduler(
      new ForkJoinPool(tc)))
    // Collect thread ids to measure CPU time:
    val threadIds = new java.util.concurrent.ConcurrentSkipListSet[Long]()
    for (_ <- 0 until 2 * Scheduler.threadCount) {
      queryManager.scheduler.addThread({
        threadIds.add(Thread.currentThread().getId())
        Thread.sleep(100)
      })
    }
    queryManager.scheduler.waitUntilDone()
    val threadIdSet = threadIds.asScala.toSet
    val threadMXBean = ManagementFactory.getThreadMXBean()
    val initialCPUTime = threadIdSet.map(threadMXBean.getThreadCpuTime).sum
    val (maybeTime, memory) = CollectEvaluationData.measureTimeAndMemory(
      _ => solveQueries(queryManager, backwardQueries.map(_._1)),
      timeout,
      _ => queryManager.cancel()
    )
    val finalCPUTime = threadIdSet.map(threadMXBean.getThreadCpuTime).sum
    val result = ExperimentResult(
      runningTimeInMillis = maybeTime.map(_._2.toMillis).getOrElse(timeout.toMillis),
      cpuTimeInMillis = (finalCPUTime - initialCPUTime).nanos.toMillis,
      memoryUsageInBytes = memory,
      numberOfRequestedQueries = backwardQueries.size,
      numberOfAllQueries = queryManager.queryCount,
      numberOfCallsResolutionQueries = queryManager.queriedCallCount,
      numberOfPropertyAccessQueries = queryManager.queriedPropertyAccessCount,
      totalNumberOfCallNodes = queryManager.totalCallNodeCount,
      totalNumberOfPropertyAccesses = queryManager.totalPropertyAccessCount,
      timeoutInMillis = timeout.toMillis,
      queryIndices = backwardQueries.map(_._2).toSet,
      benchmarkName = inputFile.baseName,
      fullFilePath = inputFile.toString,
      callEdgesFound = queryManager.getCallGraph.size(),
      isWholeProgram = isWholeProgram,
      linesInFile = linesInFile,
      threadCount = tc,
      tajsNodeIndices = nodeIndices,
      iterations = maybeTime.map(_._1).getOrElse(-1)
    )
    queryManager.printStatus() // ensure nothing is garbage collected before the measurement is taken
    os.write.over(resultsFile, result.asJson.toString)
    result
  }

  private def solveQueries(queryManager: QueryManager, queries: Iterable[BackwardQuery]): Int = {
    queries.foreach(query => {
      queryManager.getOrStartBackwardQuery(query, Optional.empty())
    })
    val its = queryManager.solve(debugFlag)
    System.err.println(s"CG status after solve: ${queryManager.getCallGraph.status()}")
    its
  }


  def measureTime[A](thunk: Unit => A, timeout: FiniteDuration, cancelThunk: Unit => Unit): Option[(A, FiniteDuration)] = {
    try {
      Await.result(Future({
        val t0 = System.currentTimeMillis()
        val result = thunk()
        val t1 = System.currentTimeMillis()
        Some((result, t1.millis - t0.millis))
      }), timeout)
    } catch {
      case _: TimeoutException =>
        System.err.println("Timeout reached")
        cancelThunk()
        None
    }
  }

  /** Note that the caller has to ensure any significant pieces of memory used/created by thunk
   * are still referenced from somewhere after `thunk` finishes executing to ensure that it is
   * not garbage-collected before the measurement is taken. */
  def measureMemory[A](thunk: => A): (A, Long) = {
    System.gc()
    val result = thunk
    val memoryMXBean = ManagementFactory.getMemoryMXBean
    (result, memoryMXBean.getHeapMemoryUsage.getUsed + memoryMXBean.getNonHeapMemoryUsage.getUsed)
  }

  def measureTimeAndMemory[A](thunk: Unit => A, timeout: FiniteDuration, cancelThunk: Unit => Unit): (Option[(A, FiniteDuration)], Long) = {
    measureMemory(measureTime(thunk, timeout, cancelThunk))
  }

  private def ensureDirExists(dir: os.Path): os.Path = {
    os.makeDir.all(dir)
    dir
  }

  private def resultsDir = ensureDirExists(os.pwd / "results")

}