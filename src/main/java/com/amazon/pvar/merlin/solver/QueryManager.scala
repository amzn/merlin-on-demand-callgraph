/* Copyright 2022-2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License. */

package com.amazon.pvar.merlin.solver

import com.amazon.pvar.merlin.ir.{
  Allocation,
  FlowgraphUtils,
  FunctionAllocation,
  MethodCall,
  NodeState,
  Register,
  Value
}
import com.amazon.pvar.merlin.livecollections.{LiveSet, Scheduler}
import com.amazon.pvar.merlin.solver.flowfunctions.ForwardFlowFunctions
import dk.brics.tajs.flowgraph.{AbstractNode, FlowGraph}
import dk.brics.tajs.flowgraph
import dk.brics.tajs.flowgraph.jsnodes.{CallNode, DeclareFunctionNode, LoadNode, ReadPropertyNode, WritePropertyNode}
import sync.pds.solver.nodes.Node

import java.time.temporal.{ChronoUnit, Temporal, TemporalUnit}
import java.time.{Duration, Instant}
import java.util
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class QueryManager(flowGraph: FlowGraph, val scheduler: Scheduler) {
  import QueryManager.{BackwardQuery, ForwardQuery}

  private val backwardSolvers =
    mutable.Map.empty[BackwardQuery, BackwardMerlinSolver]

  private val forwardSolvers =
    mutable.Map.empty[ForwardQuery, ForwardMerlinSolver]

  // for debugging performance issues with excessive number of queued tasks
  private val backwardSolversToLiveSets =
    mutable.Map.empty[BackwardQuery, Any]
  private val forwardSolversToLiveSets =
    mutable.Map.empty[ForwardQuery, Set[LiveSet[Any]]]

  private val queriedCallNodes = mutable.Set.empty[CallNode]

  // separate counters to allow lock-free access for status reporting
  private val backwardSolverCount = new AtomicInteger(0)
  private val forwardSolverCount = new AtomicInteger(0)

  private val callGraph = new CallGraph(scheduler)

  private val pointsToGraph = new PointsToGraph(scheduler)

  private val queryDependencyGraph = new QueryDependencyGraph()

  private val queryNodes = mutable.Map.empty[Query, QueryNode]

  private var solveStart: Option[Instant] = None

  private def registerQuery(query: Query): Unit = {
    queryDependencyGraph.ensureVertexInGraph(getNodeForQuery(query))
  }

  def getOrCreateBackwardSolver(
      backwardQuery: BackwardQuery,
      resolvingCallNode: java.util.Optional[CallNode]
  ): (BackwardMerlinSolver, Boolean) = {
    registerQuery(new Query(backwardQuery, false))
    if (resolvingCallNode.isPresent) {
      queriedCallNodes.synchronized {
        queriedCallNodes.addOne(resolvingCallNode.get())
      }
    }
    var newSolverAdded = false
    val result = backwardSolvers.synchronized {
      backwardSolvers.getOrElseUpdate(
        backwardQuery, {
          val solver =
            new BackwardMerlinSolver(this, backwardQuery)
          solver.setFunctionQuery(true)
          backwardSolverCount.incrementAndGet()
          newSolverAdded = true
          solver
        }
      )
    }
    (result, newSolverAdded)
  }

  private def solveAndLogExceptions(query: Query, solver: MerlinSolver): Unit = {
    try {
      solver.solve()
    } catch {
      case exn: Exception =>
        System.err.println(s"$query threw $exn")
        exn.printStackTrace()
        getNodeForQuery(query).registerError(exn)
    }
  }

  def getOrStartBackwardQuery(
      backwardQuery: BackwardQuery,
      resolvingCallNode: java.util.Optional[CallNode],
      answerSet: Any
  ): BackwardMerlinSolver = {
    val (solver, newSolverAdded) = getOrCreateBackwardSolver(backwardQuery, resolvingCallNode)
    // This should only be done if new solver is actually created!!!!!
    if (newSolverAdded) {
      scheduler.addThread({
        solveAndLogExceptions(new Query(backwardQuery, false), solver)
      })
    }
    backwardSolversToLiveSets.synchronized {
      backwardSolversToLiveSets(backwardQuery) = answerSet
    }
    solver
  }

  def getOrStartBackwardQuery(
      backwardQuery: BackwardQuery,
      resolvingCallNode: java.util.Optional[CallNode]
  ): BackwardMerlinSolver = {
    getOrStartBackwardQuery(backwardQuery, resolvingCallNode, null)
  }

  def getOrCreateForwardSolver(
      forwardQuery: ForwardQuery
  ): (ForwardMerlinSolver, Boolean) = {
    registerQuery(new Query(forwardQuery, true))
    var newSolverAdded = false
    val result = forwardSolvers.synchronized {
      forwardSolvers.getOrElseUpdate(
        forwardQuery, {
          forwardSolverCount.incrementAndGet()
          newSolverAdded = true
          new ForwardMerlinSolver(this, forwardQuery)
        }
      )
    }
    (result, newSolverAdded)
  }

  def getOrStartForwardQuery(
      forwardQuery: ForwardQuery
  ): ForwardMerlinSolver = {
    val (solver, newSolverAdded) = getOrCreateForwardSolver(forwardQuery)
    if (newSolverAdded) {
      scheduler.addThread({
        solveAndLogExceptions(new Query(forwardQuery, true), solver)
      })
    }
    solver
  }

  def getPointsToGraph: PointsToGraph = pointsToGraph

  def getCallGraph: CallGraph = callGraph

  def addPointsToFact(location: dk.brics.tajs.flowgraph.jsnodes.Node, value: Value, alloc: Allocation): Unit = {
    val successors = FlowgraphUtils.successorsOf(location).toList.asScala
    successors
      .collect({ case callNode: CallNode => callNode })
      .foreach(callNode => {
        (value, alloc) match {
          case (reg: Register, funcAlloc: FunctionAllocation)
              if callNode.getFunctionRegister != -1 &&
                reg.getId == callNode.getFunctionRegister &&
                reg.getContainingFunction == callNode.getBlock.getFunction =>
            getCallGraph.addEdge(callNode, funcAlloc.getAllocationStatement.getFunction)
          case _ =>
        }
      })

    this.getPointsToGraph.addPointsToFact(location, value, alloc)
    (location, alloc, value) match {
      case (callNode: CallNode, functionAllocation: FunctionAllocation, reg: Register)
          if callNode.getFunctionRegister != -1 &&
            reg.getId == callNode.getFunctionRegister &&
            reg.getContainingFunction == callNode.getBlock.getFunction =>
        this.getCallGraph.addEdge(callNode, functionAllocation.getAllocationStatement.getFunction)
      case (callNode: CallNode, functionAllocation: FunctionAllocation, methodCall: MethodCall)
          if callNode.equals(methodCall.getCallNode) =>
        this.getCallGraph.addEdge(callNode, functionAllocation.getAllocationStatement.getFunction)
      case _ =>
    }
  }

  def solve(): Unit = solve(false)

  /** Run all solvers to completion */
  def solve(reportStatus: Boolean): Int = {
    solveStart = Some(Instant.now())
    // After solving, we still need to handle unresolved function calls, which may each
    // trigger additional flows in other solvers leading to more unresolved calls. We handle
    // this by computing the fixed point of repeatedly adding data flows for unresolved
    // calls until no new data flows are found
    var stillIterating = true
    var iteration = 0
    if (reportStatus) {
      startStatusReporting(1000)
    }
    while (stillIterating) {
      System.err.println(s"Iteration $iteration")
      scheduler.waitUntilDone()
      stillIterating = false
      for (solver <- backwardSolvers.values ++ forwardSolvers.values) {
        if (solver.addDataFlowsForUnresolvedFunctionCalls()) {
          stillIterating = true
        }
      }
      iteration += 1
    }
    if (reportStatus) {
      stopStatusReporting()
    }
    iteration
  }

  def getNodeForQuery(query: Query) = queryNodes.get(query) match {
    case Some(node) => node
    case None =>
      val node = new QueryNode(query)
      queryNodes(query) = node
      node
  }

  def registerQueryDependency(initialQuery: Query, subQuery: Query): Unit = {
    queryDependencyGraph.addDependency(getNodeForQuery(initialQuery), getNodeForQuery(subQuery))
  }

  def errorsImpactingQuery(query: Query): java.util.Map[Query, java.util.Set[Exception]] = {
    queryDependencyGraph.errorsImpactingQuery(getNodeForQuery(query))
  }

  def printStatus(): Unit = {
    val runningTime = solveStart match {
      case Some(startInstant) =>
        val duration = Duration.between(startInstant, Instant.now())
        s"[$duration] "
      case _ => ""
    }
    val managerStatus = s"fwd solvers: ${forwardSolverCount.get()}, bwd solvers: ${backwardSolverCount.get()}"
    val schedulerStatus = scheduler.status()
    val dependencyStatus = queryDependencyGraph.status()
    System
      .err
      .println(
        s"$runningTime$managerStatus - $callNodeCoverageStatus - $propertyAccessCoverageStatus - $schedulerStatus - $dependencyStatus - handlerStats: ${HandlerStats.status} - ${callGraph.status()}"
      )
  }

  def queriedCallCount: Int = queriedCallNodes.synchronized {
    queriedCallNodes.size
  }

  def callNodeCoverageStatus: String = {
    s"call nodes queried: ${percentageStatus(queriedCallCount, totalCallNodeCount)}"
  }

  private val queriedPropertyAccess: mutable.Set[AbstractNode] = mutable.Set.empty

  def registerPropertyAccessQuery(node: AbstractNode): Unit = queriedPropertyAccess.synchronized {
    queriedPropertyAccess.addOne(node)
  }
  def queriedPropertyAccessCount = queriedPropertyAccess.synchronized { queriedPropertyAccess.size }

  def propertyAccessCoverageStatus: String = {
    s"property access queried: ${percentageStatus(queriedPropertyAccessCount, totalPropertyAccessCount)}"
  }

  def percentageStatus(current: Long, total: Long): String = {
    val percentage = 100 * (if (total == 0) 1 else current.toDouble / total.toDouble)
    f"$current / $total ($percentage%2.2f%%)"
  }

  val totalCallNodeCount = FlowgraphUtils.allCallNodes(flowGraph).count()

  val totalPropertyAccessCount = FlowgraphUtils.propertyAccessNodes(flowGraph).count()

  def queryCount: Int =
    backwardSolvers.synchronized { backwardSolvers.size } + forwardSolvers.synchronized { forwardSolvers.size }

  private var statusThread: Option[Thread] = None
  def startStatusReporting(intervalMillis: Long): Unit = {
    if (statusThread.isDefined) {
      System.err.println(s"Status reporting already enabled")
    } else {
      val thread = new Thread(() => {
        var done = false
        while (!done) {
          try {
            printStatus()
            Thread.sleep(intervalMillis)
          } catch {
            case _: InterruptedException => done = true
          }
        }
      })
      thread.start()
      statusThread = Some(thread)
    }
  }

  def stopStatusReporting(): Unit = statusThread match {
    case Some(thread) => thread.interrupt()
    case None =>
  }

  def cancel(): Unit = {
    stopStatusReporting()
    scheduler.cancel()
  }

  val taskQueueUpperBound: Long = {
    // Maximum number of handler invocations:
    // for each dependency edge from q1 to q2, the handler may be invoked |answers(q2)| times
    // where answers(q) returns the liveset(s) that corresponds to its answers
    var upperBound = 0L
    backwardSolversToLiveSets.synchronized {
      for ((backwardQuery, answer) <- backwardSolversToLiveSets) {
        queryDependencyGraph.synchronized {
          val queryNode = getNodeForQuery(new Query(backwardQuery, false))
          queryDependencyGraph
            .directDependenciesOf(queryNode)
            .forEach(dep => {
              val dependencySolver =
                if (dep.isForward) forwardSolvers(dep.queryValue) else backwardSolvers(dep.queryValue)
              val dependencySolverSet = if (!dep.isForward) backwardSolversToLiveSets(dep.queryValue) else null
              dependencySolverSet match {
                case ls: LiveSet[Any] if ls != null =>
                  upperBound += ls.currentSize
              }
            })
        }
      }
    }
    upperBound
  }
}

object QueryManager {

  type BackwardQuery = Node[NodeState, Value]
  type ForwardQuery = Node[NodeState, Value]

  def of(flowGraph: FlowGraph): QueryManager = {
    FlowgraphUtils.currentFlowGraph = flowGraph
    new QueryManager(flowGraph, new Scheduler())
  }
}
