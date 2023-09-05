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

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Trait for collections allowing to register callbacks when new events are
  * added.
  */
sealed trait LiveCollection[A] {

  /** Register a handler for elements in this collection. When a handler is
    * added, it is run on all existing elements on the collection, and invoked
    * whenever a new element is added.
    *
    * Does not block but may run handler on existing elements in new tasks.
    */
  def onAdd(handler: Handler[A]): Unit

  def toSet: Set[A]

  def toJavaSet: java.util.Set[A] = this.toSet.asJava

  /** Creates a new live collection containing the elements of both. Note that
    * adding elements to either of the underlying live collection will invoke
    * handlers on the union as well.
    */
  def union(other: LiveCollection[A]): LiveCollection[A] =
    UnionLiveSet(this, other)

  /** Obtain a new live collection by mapping a function. Handlers on the
    * resulting live collection will be invoked with `func(elem)` when `elem` is
    * added to the original live collection.
    */
  def map[B](func: A => B): LiveCollection[B] = MappedLiveSet(this, func)

  /** Obtain a live collection containing only elements satisfying `pred`.
    * Adding a `elem` to the original collection will invoke any handlers
    * registered on the filtered collection if they satisfy `pred`.
    */
  def filter(pred: A => Boolean): LiveCollection[A] =
    FilteredLiveSet(this, pred)
}

/** A set that allows registering callbacks for new elements that are added.
  * Each live set is associated with a `Scheduler` to allow waiting for all
  * computations related to this liveset to complete.
  */
class LiveSet[A](sched: Scheduler) extends LiveCollection[A] {
  private val handlers: mutable.Set[Handler[A]] = mutable.Set.empty
  private val elems: mutable.Set[A] = mutable.Set.empty

  override def onAdd(handler: Handler[A]): Unit = synchronized {
    if (!handlers.contains(handler)) {
      handlers += handler
      elems.foreach(answer => {
        sched.addThread(handler.run(answer))
      })
    }
  }

  def currentSize: Int = elems.synchronized { elems.size }

  /** Adds an element to the LiveSet and runs any handlers registered on it. */
  def add(elem: A): Boolean = synchronized {
    if (!elems.contains(elem)) {
      elems += elem
      handlers.foreach(handler => {
        sched.addThread(handler.run(elem))
      })
      true
    } else {
      false
    }
  }

  /** Block until all computations on the same scheduler have finished. */
  def waitUntilStable(): Unit = sched.waitUntilDone()

  /** Wait for live set to stabilize and convert to an ordinary (Scala) `Set`.
    */
  override def toSet: Set[A] = {
    waitUntilStable()
    elems.toSet
  }

}

object LiveSet {
  // Java-friendly constructors
  def create[A](sched: Scheduler): LiveSet[A] = new LiveSet(sched)
}

private case class MappedLiveSet[A, B](liveSet: LiveCollection[A], func: A => B)
    extends LiveCollection[B] {
  override def onAdd(handler: Handler[B]): Unit =
    liveSet.onAdd(
      WrappedHandler(handler.withRun(a => handler.run(func(a))), this)
    )

  override def toSet: Set[B] = liveSet.toSet.map(func)
}

private case class FilteredLiveSet[A](
    liveSet: LiveCollection[A],
    pred: A => Boolean
) extends LiveCollection[A] {
  override def onAdd(handler: Handler[A]): Unit = liveSet.onAdd(
    WrappedHandler(
      handler.withRun(a =>
        if (pred(a)) {
          handler.run(a)
        }
      ),
      this
    )
  )

  override def toSet: Set[A] = liveSet.toSet.filter(pred)
}

private case class UnionLiveSet[A](
    lhs: LiveCollection[A],
    rhs: LiveCollection[A]
) extends LiveCollection[A] {
  override def onAdd(handler: Handler[A]): Unit = {
    lhs.onAdd(WrappedHandler(handler, this))
    rhs.onAdd(WrappedHandler(handler, this))
  }

  override def toSet: Set[A] = lhs.toSet ++ rhs.toSet
}

/** Used to ensure that handlers on derived live collections (obtained via .map,
  * .filter, or .union) are not treated as identical to handlers on the
  * underlying set. See test case "run same listener if registered on mapped
  * set" in LiveSetSpec. This construction relies on LiveCollections only
  * supporting reference equality, so wrapping the same handler twice but with
  * different live collections will ensure that the two wrapped handlers are
  * treated as distinct.
  */
private case class WrappedHandler[A](
    wrappedHandler: Handler[A],
    wrappedCollection: Any // Only used for disambiguating handlers
) extends Handler[A] {
  override def run(a: A): Unit = wrappedHandler.run(a)

  override def withRun[B](newRun: B => Unit): Handler[B] =
    this.copy(wrappedHandler = wrappedHandler.withRun(newRun))
}
