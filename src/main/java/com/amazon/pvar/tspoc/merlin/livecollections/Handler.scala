package com.amazon.pvar.tspoc.merlin.livecollections

import java.util.function.Consumer

/** Handlers for reacting to elements being added to LiveCollections. If the
  * implementating type provides a sensible hashCode and equals implementation,
  * then duplicate handlers will not be registered on the same collection.
  */
trait Handler[A] {
  def run(a: A): Unit
  def withRun[B](newRun: B => Unit): Handler[B]
}

/** Uses `tag` to distinguish handlers (and does not use `cont` in equals and
  * hashCode computation).
  */
final case class TaggedHandler[T, A](tag: T, cont: A => Unit)
    extends Handler[A] {
  def run(a: A): Unit = cont(a)

  override def equals(obj: Any): Boolean = {
    obj match {
      case TaggedHandler(tag2, _) => tag2 == tag
      case _                      => false
    }
  }

  override def withRun[B](newRun: B => Unit): Handler[B] =
    this.copy(cont = newRun)

  override def hashCode(): Int = tag.hashCode()

  override def toString: String = s"Handler:${tag.toString}"
}

object TaggedHandler {

  /** Convenience function for creating tagged handlers from Java without
    * needing a dummy return.
    */
  def create[T, A](tag: T, javaFunc: Consumer[A]): TaggedHandler[T, A] =
    TaggedHandler(tag, a => javaFunc.accept(a))
}
