/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.cookbook

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.testkit.TimingTest
import org.apache.pekko.{ Done, NotUsed }

import scala.concurrent._
import scala.concurrent.duration._

class RecipeAdhocSource extends RecipeSpec {

  // #adhoc-source
  def adhocSource[T](source: Source[T, _], timeout: FiniteDuration, maxRetries: Int): Source[T, _] =
    Source.lazySource(() =>
      source
        .backpressureTimeout(timeout)
        .recoverWithRetries(maxRetries,
          {
            case t: TimeoutException =>
              Source.lazySource(() => source.backpressureTimeout(timeout)).mapMaterializedValue(_ => NotUsed)
          }))
  // #adhoc-source

  "Recipe for adhoc source" must {
    "not start the source if there is no demand" taggedAs TimingTest in {
      val isStarted = new AtomicBoolean()
      adhocSource(Source.empty.mapMaterializedValue(_ => isStarted.set(true)), 200.milliseconds, 3)
        .runWith(TestSink[Int]())
      Thread.sleep(300)
      isStarted.get() should be(false)
    }

    "start the source when there is a demand" taggedAs TimingTest in {
      val sink = adhocSource(Source.repeat("a"), 200.milliseconds, 3).runWith(TestSink[String]())
      sink.requestNext("a")
    }

    "shut down the source when the next demand times out" taggedAs TimingTest in {
      val shutdown = Promise[Done]()
      val sink = adhocSource(Source.repeat("a").watchTermination() { (_, term) =>
          shutdown.completeWith(term)
        }, 200.milliseconds, 3).runWith(TestSink[String]())

      sink.requestNext("a")
      Thread.sleep(200)
      shutdown.future.failed.futureValue shouldBe a[TimeoutException]
    }

    "not shut down the source when there are still demands" taggedAs TimingTest in {
      val shutdown = Promise[Done]()
      val sink = adhocSource(Source.repeat("a").watchTermination() { (_, term) =>
          shutdown.completeWith(term)
        }, 200.milliseconds, 3).runWith(TestSink[String]())

      sink.requestNext("a")
      Thread.sleep(100)
      sink.requestNext("a")
      Thread.sleep(100)
      sink.requestNext("a")
      Thread.sleep(100)
      sink.requestNext("a")
      Thread.sleep(100)
      sink.requestNext("a")
      Thread.sleep(100)

      shutdown.isCompleted should be(false)
    }

    "restart upon demand again after timeout" taggedAs TimingTest in {
      val shutdown = Promise[Done]()
      val startedCount = new AtomicInteger(0)

      val source = Source.empty.mapMaterializedValue(_ => startedCount.incrementAndGet()).concat(Source.repeat("a"))

      val sink = adhocSource(source.watchTermination() { (_, term) =>
          shutdown.completeWith(term)
        }, 200.milliseconds, 3).runWith(TestSink[String]())

      sink.requestNext("a")
      startedCount.get() should be(1)
      Thread.sleep(200)
      shutdown.future.failed.futureValue shouldBe a[TimeoutException]
    }

    "restart up to specified maxRetries" taggedAs TimingTest in {
      val shutdown = Promise[Done]()
      val startedCount = new AtomicInteger(0)

      val source = Source.empty.mapMaterializedValue(_ => startedCount.incrementAndGet()).concat(Source.repeat("a"))

      val sink = adhocSource(source.watchTermination() { (_, term) =>
          shutdown.completeWith(term)
        }, 200.milliseconds, 3).runWith(TestSink[String]())

      sink.requestNext("a")
      startedCount.get() should be(1)

      Thread.sleep(500)
      shutdown.isCompleted should be(true)

      Thread.sleep(500)
      sink.requestNext("a")
      startedCount.get() should be(2)

      Thread.sleep(500)
      sink.requestNext("a")
      startedCount.get() should be(3)

      Thread.sleep(500)
      sink.requestNext("a")
      startedCount.get() should be(4) // startCount == 4, which means "re"-tried 3 times

      Thread.sleep(500)
      sink.expectError().getClass should be(classOf[TimeoutException])
      sink.request(1) // send demand
      sink.expectNoMessage(200.milliseconds) // but no more restart
    }
  }
}
