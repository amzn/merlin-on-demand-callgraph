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

package com.amazon.pvar.merlin.ir

import com.amazon.pvar.merlin.DebugUtils
import dk.brics.tajs.flowgraph
import dk.brics.tajs.flowgraph.FlowGraph
import dk.brics.tajs.flowgraph.jsnodes.{DeclareFunctionNode, DeclareVariableNode, ReadVariableNode}
import jdk.nashorn.internal.runtime.Debug

import scala.collection.mutable
import scala.jdk.CollectionConverters._

//sealed trait NAryTree[T] {}
//
//final case class Leaf[T](elem: T) extends NAryTree[T]
//final case class Node[T][(


/** This resolver assumes that you never write to a variable of the same name as a declared function!
 * This assumption is currently not checked. */
final case class ReadVarResolver(flowGraph: FlowGraph) {

  private val funcIndex: mutable.Map[ReadVariableNode, Set[flowgraph.Function]] = mutable.Map.empty

  // empty set denotes unresolved
  def resolveReadVar(readVar: ReadVariableNode): Set[flowgraph.Function] = {
    funcIndex.get(readVar).getOrElse(Set.empty)
  }

  def resolveReadVarJ(readVariableNode: ReadVariableNode): java.util.Set[flowgraph.Function] = {
    resolveReadVar(readVariableNode).asJava
  }

  FlowgraphUtils.allNodes(flowGraph).forEach({
    case declareFunctionNode: DeclareFunctionNode if declareFunctionNode.getFunction.getName != null =>
      propagateFuncDecl(declareFunctionNode)
    case _ =>
  })
  DebugUtils.debug("Done indexing read variable nodes")

  private def propagateFuncDecl(funcDecl: DeclareFunctionNode): Unit = {
    val funcName = funcNameOf(funcDecl)
    val nodesAtSameLevel = FlowgraphUtils.allNodesInFunction(funcDecl.getBlock.getFunction).toList.asScala
    nodesAtSameLevel.foreach({
      case readVariableNode: ReadVariableNode if readVariableNode.getVariableName == funcName =>
        indexReadVar(readVariableNode, funcDecl.getFunction)
      case declAtSameLevel: DeclareFunctionNode => propagateFuncDeclIntoChild(funcDecl, declAtSameLevel.getFunction)
      case _ =>
    })
  }

  private def propagateFuncDeclIntoChild(funcDecl: DeclareFunctionNode, child: flowgraph.Function): Unit = {
    val nodesInChild = FlowgraphUtils.allNodesInFunction(child).toList.asScala
    val funcName = funcNameOf(funcDecl)
    val boundVars = child.getParameterNames.asScala.toSet ++
      nodesInChild
        .collect({
          case declVar: DeclareVariableNode => declVar.getVariableName
          case declareFunctionNode: DeclareFunctionNode if declareFunctionNode.getFunction.getName != null => declareFunctionNode.getFunction.getName
        })
        .toSet
    if (!boundVars.contains(funcName)) {
      nodesInChild.foreach({
        case readVariableNode: ReadVariableNode if readVariableNode.getVariableName == funcName =>
          indexReadVar(readVariableNode, funcDecl.getFunction)
        case declareFunctionNode: DeclareFunctionNode => propagateFuncDeclIntoChild(funcDecl, declareFunctionNode.getFunction)
        case _ =>
      })
    }
  }

  private def indexReadVar(readVariableNode: ReadVariableNode, func: flowgraph.Function): Unit = funcIndex.get(readVariableNode) match {
    case Some(set) =>
      funcIndex(readVariableNode) = (set + func)
    case None =>
      funcIndex(readVariableNode) = Set(func)
  }

  private def funcNameOf(funcDecl: DeclareFunctionNode): String = {
    assert(funcDecl.getFunction.getName != null)
    funcDecl.getFunction.getName
  }


}
