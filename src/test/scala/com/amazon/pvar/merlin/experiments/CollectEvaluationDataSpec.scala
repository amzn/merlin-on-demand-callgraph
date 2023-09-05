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

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.time.{Millis, Span}
import scala.collection.mutable

import scala.concurrent.duration.DurationInt

class CollectEvaluationDataSpec extends AnyFlatSpec {
  "measureTime" should "measure wall clock time" in {
    val Some((result, time)) = CollectEvaluationData.measureTime(_ => {
      Thread.sleep(1000)
      1
    }, 5.seconds, _ => ???)
    result should equal (1)
    time should be >= 1000.millis
  }

  "measureMemory" should "measure memory" in {
    // data structure needs to be created here so it's not garbage collected by the end of the thunk
    val myList = mutable.Buffer.empty[Int]
    val (_, bytes) = CollectEvaluationData.measureMemory({
      (0 until 10000).foreach(myList.addOne)
    })
    val expectedMinMemory = 10000L * 4L
    bytes should be >= expectedMinMemory
  }
}

