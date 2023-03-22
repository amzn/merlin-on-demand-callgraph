package com.amazon.pvar.tspoc.merlin.livecollections

import java.util.concurrent.{ForkJoinPool, TimeUnit}

/** Mostly a wrapper around a thread pool to keep track of all threads involved
  * in a computation using `LiveSet`s.
  */
final class Scheduler(
    pool: ForkJoinPool = new ForkJoinPool(
//      Runtime.getRuntime.availableProcessors
      1
    )
) {

  def waitUntilDone(): Unit = {
    while (!pool.awaitQuiescence(1, TimeUnit.HOURS)) {}
  }

  def addThread(func: => Unit): Unit = {
    pool.execute(() => func)
  }

}

object Scheduler {
  // Java-friendly constructors (since Java does not support default parameters)
  def create(): Scheduler = new Scheduler(
    new ForkJoinPool(1)
      //Runtime.getRuntime.availableProcessors)
  )

  def create(pool: ForkJoinPool) = new Scheduler(pool)
}
