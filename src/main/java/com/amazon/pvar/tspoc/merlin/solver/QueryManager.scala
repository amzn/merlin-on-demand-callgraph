package com.amazon.pvar.tspoc.merlin.solver

import com.amazon.pvar.tspoc.merlin.ir.{NodeState, Value}
import com.amazon.pvar.tspoc.merlin.livecollections.Scheduler
import sync.pds.solver.nodes.Node

import scala.collection.mutable
import scala.util.DynamicVariable

class QueryManager() {
  import QueryManager.{BackwardQuery, ForwardQuery}

  private val backwardSolvers =
    mutable.Map.empty[BackwardQuery, BackwardMerlinSolver]

  private val forwardSolvers =
    mutable.Map.empty[ForwardQuery, ForwardMerlinSolver]

  private val callGraph = new CallGraph()

  val scheduler = new Scheduler()

  private val pointsToGraph = new PointsToGraph(scheduler)

  def getOrCreateBackwardSolver(
      backwardQuery: BackwardQuery
  ): BackwardMerlinSolver = backwardSolvers.synchronized {
    backwardSolvers.getOrElseUpdate(
      backwardQuery, {
        val solver =
          new BackwardMerlinSolver(this, backwardQuery)
        solver.setFunctionQuery(true)
        solver
      }
    )
  }

  def getOrStartBackwardQuery(
      backwardQuery: BackwardQuery
  ): BackwardMerlinSolver = {
    val solver = getOrCreateBackwardSolver(backwardQuery)
    scheduler.addThread({
      solver.solve()
    })
    solver
  }

  def getOrCreateForwardSolver(
      forwardQuery: ForwardQuery
  ): ForwardMerlinSolver = forwardSolvers.synchronized {
    forwardSolvers.getOrElseUpdate(
      forwardQuery,
      new ForwardMerlinSolver(this, forwardQuery)
    )
  }

  def getOrStartForwardQuery(
      forwardQuery: ForwardQuery
  ): ForwardMerlinSolver = {
    val solver = getOrCreateForwardSolver(forwardQuery)
    scheduler
      .addThread({
        solver.solve()
      })
    solver
  }

  def getPointsToGraph: PointsToGraph = pointsToGraph

  def getCallGraph: CallGraph = callGraph
}

/** Helper object to manage a single query manager via dynamic scoping rather
  * than a singleton (simplifies testing)
  */
object QueryManager {

  type BackwardQuery = Node[NodeState, Value]
  type ForwardQuery = Node[NodeState, Value]

}
