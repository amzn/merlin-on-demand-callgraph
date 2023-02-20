package com.amazon.pvar.tspoc.merlin.solver

import com.amazon.pvar.tspoc.merlin.ir.{Allocation, FunctionAllocation, MethodCall, NodeState, Register, Value}
import com.amazon.pvar.tspoc.merlin.livecollections.Scheduler
import dk.brics.tajs.flowgraph.jsnodes.CallNode
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

  def addPointsToFact(location: dk.brics.tajs.flowgraph.jsnodes.Node, value: Value, alloc: Allocation): Unit = {
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
}

object QueryManager {

  type BackwardQuery = Node[NodeState, Value]
  type ForwardQuery = Node[NodeState, Value]

}
