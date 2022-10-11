package com.amazon.pvar.tspoc.merlin.livecollections

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.time.{Millis, Span}

import java.util.concurrent.atomic.AtomicInteger

class LiveSetSpec extends AnyFlatSpec with TimeLimitedTests {

  val timeLimit: Span = Span(10000, Millis)

  "A LiveSet" should "work" in {
    val sched = new Scheduler()
    val ls: LiveSet[Int] = new LiveSet(sched)
    ls.onAdd(
      TaggedHandler(
        "h",
        n =>
          if (n < 10) {
            ls.add(n + 1)
          }
      )
    )
    ls.add(1)
    ls.toSet should equal((1 to 10).toSet)
  }

  it should "terminate with recursive asks" in {
    val sched = new Scheduler()
    val ls1: LiveSet[Int] = new LiveSet(sched)

    def cont(n: Int): Unit = ls1.onAdd(TaggedHandler("h", cont))
    val handler = TaggedHandler("h", cont)
    ls1.onAdd(handler)
    ls1.add(1)
    ls1.toSet should equal(Set(1))
  }

  it should "answer two mutually recursive queries correctly" in {
    val sched = new Scheduler()
    val ls1 = new LiveSet[Int](sched)
    val ls2 = new LiveSet[Int](sched)
    ls1.onAdd(
      TaggedHandler(
        "h1",
        n => {
          for (i <- 1 to math.min(10, n + 1)) {
            ls2.add(i)
          }
        }
      )
    )
    ls2.onAdd(
      TaggedHandler(
        "h2",
        n => {
          for (i <- 1 to math.min(10, n + 1)) {
            ls1.add(i)
          }
        }
      )
    )
    ls1.add(1)
    ls2.add(1)
    ls1.toSet should equal((1 to 10).toSet)
    ls2.toSet should equal((1 to 10).toSet)
  }

  it should "not run a duplicate listener" in {
    val sched = new Scheduler()
    val ls1 = new LiveSet[Int](sched)
    val counter: AtomicInteger = new AtomicInteger(0)
    // Two equal handlers with same hashCode:
    val handler1 = TaggedHandler("abc", (_: Int) => { counter.addAndGet(1) })
    val handler2 = TaggedHandler("abc", (_: Int) => { counter.addAndGet(1) })
    ls1.onAdd(handler1)
    ls1.onAdd(handler2)
    ls1.add(1)
    sched.waitUntilDone()
    counter.get() should equal(1)
  }

  private def assertListenerOnDerivedSetIsRun[A](
      underlyingSet: LiveSet[A],
      derivedSet: LiveCollection[A],
      elemToAdd: A
  ): Unit = {
    val counter: AtomicInteger = new AtomicInteger(0)
    val handler = TaggedHandler("test", (_: A) => { counter.addAndGet(1) })
    underlyingSet.onAdd(handler)
    derivedSet.onAdd(handler)
    underlyingSet.add(elemToAdd)
    underlyingSet.toSet // wait for sets to stabilize
    derivedSet.toSet
    counter.get() should equal(2)
  }

  it should "run same listener if registered on mapped set" in {
    val sched = new Scheduler()
    val underlyingSet = new LiveSet[Int](sched)
    val derivedSet = underlyingSet.map(x => x + 1)
    assertListenerOnDerivedSetIsRun(underlyingSet, derivedSet, 1)
  }

  it should "run same listener if registered on filtered set" in {
    val sched = new Scheduler()
    val underlyingSet = new LiveSet[Int](sched)
    val derivedSet = underlyingSet.filter(_ < 10)
    assertListenerOnDerivedSetIsRun(underlyingSet, derivedSet, 1)
  }

  it should "run same listener if registered on union of sets (LHS)" in {
    val sched = new Scheduler()
    val underlyingSet1 = new LiveSet[Int](sched)
    val underlyingSet2 = new LiveSet[Int](sched)
    val derivedSet = underlyingSet1 union underlyingSet2
    assertListenerOnDerivedSetIsRun(underlyingSet1, derivedSet, 1)
  }

  it should "run same listener if registered on union of sets (RHS)" in {
    val sched = new Scheduler()
    val underlyingSet1 = new LiveSet[Int](sched)
    val underlyingSet2 = new LiveSet[Int](sched)
    val derivedSet = underlyingSet1 union underlyingSet2
    assertListenerOnDerivedSetIsRun(underlyingSet2, derivedSet, 1)
  }

}
