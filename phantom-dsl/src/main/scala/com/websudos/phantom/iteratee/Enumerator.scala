/*
 * Copyright 2013 websudos ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.websudos.phantom.iteratee

import java.util.{ ArrayDeque => JavaArrayDeque, Deque => JavaDeque }
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.JavaConversions._
import com.datastax.driver.core.{ ResultSet, Row }
import play.api.libs.iteratee.{
  Enumerator => PlayEnum,
  Iteratee => PlayIter,
  Input,
  Step,
  Cont,
  Done,
  Error
}


object Enumerator {
  private[this] def enumerate[E](it: Iterator[E])(implicit ctx: scala.concurrent.ExecutionContext): PlayEnum[E] = {
    PlayEnum.unfold[scala.collection.Iterator[E], E](it: scala.collection.Iterator[E])({ currentIt =>
      if (currentIt.hasNext) {
        val next = currentIt.next()
        Some(currentIt -> next)
      }
      else {
        None
      }
    })
  }

  def enumerator(r: ResultSet)(implicit ctx: scala.concurrent.ExecutionContext): PlayEnum[Row] =
    enumerate[Row](r.iterator())

  def unfold(resultSet: ResultSet)(implicit ctx: scala.concurrent.ExecutionContext): PlayEnum[Row] = {

    new PlayEnum[Row] {

      val rs = resultSet
      val it = rs.iterator

      def apply[A](iter: PlayIter[Row, A]): Future[PlayIter[Row, A]] = {
        iter.fold {
          case Step.Cont(k) => fetch[A](k)
          case Step.Done(a, e) => Future.successful(Done(a, e))
          case Step.Error(msg, inp) => Future.successful(Error(msg, inp))
        }
      }

      def fetch[A](k: Input[Row] => PlayIter[Row, A]): Future[PlayIter[Row, A]] = {

        val available = resultSet.getAvailableWithoutFetching

        if (!rs.isExhausted) {
          // prefetch if we are running low on results.
          if (available < 100 && !resultSet.isFullyFetched)
            rs.fetchMoreResults
          if (available < 1)
            Future(it.next).map(row => k(Input.El(row)))
          else
            Future.successful(k(Input.El(it.next)))
        } else {
          Future.successful(Cont(k))
        }
      }
    }
  }
}

/**
 * Contains the default ExecutionContext used by Iteratees.
 */
private object Execution {

  def defaultExecutionContext: ExecutionContext = Implicits.defaultExecutionContext

  object Implicits {
    implicit def defaultExecutionContext: ExecutionContext = Execution.trampoline
    implicit def trampoline: ExecutionContext = Execution.trampoline
  }

  /**
   * Executes in the current thread. Uses a thread local trampoline to make sure the stack
   * doesn't overflow. Since this ExecutionContext executes on the current thread, it should
   * only be used to run small bits of fast-running code. We use it here to run the internal
   * iteratee code.
   *
   * Blocking should be strictly avoided as it could hog the current thread.
   * Also, since we're running on a single thread, blocking code risks deadlock.
   */
  val trampoline: ExecutionContext = new ExecutionContext {
    private[this] val cores = Runtime.getRuntime.availableProcessors()
    private[this] val local = new ThreadLocal[JavaDeque[Runnable]]

    def execute(runnable: Runnable): Unit = {
      @volatile var queue = local.get()
      if (queue == null) {
        // Since there is no local queue, we need to install one and
        // start our trampolining loop.
        try {
          queue = new JavaArrayDeque(cores)
          queue.addLast(runnable)
          local.set(queue)
          while (!queue.isEmpty) {
            val runnable = queue.removeFirst()
            runnable.run()
          }
        } finally {
          // We've emptied the queue, so tidy up.
          local.set(null)
        }
      } else {
        // There's already a local queue that is being executed.
        // Just stick our runnable on the end of that queue.
        queue.addLast(runnable)
      }
    }

    def reportFailure(t: Throwable): Unit = t.printStackTrace()
  }

  /**
   * Executes in the current thread. Calls Runnables directly so it is possible for the
   * stack to overflow. To avoid overflow the `trampoline`
   * can be used instead.
   *
   * Blocking should be strictly avoided as it could hog the current thread.
   * Also, since we're running on a single thread, blocking code risks deadlock.
   */
  val overflowingExecutionContext: ExecutionContext = new ExecutionContext {

    def execute(runnable: Runnable): Unit = {
      runnable.run()
    }

    def reportFailure(t: Throwable): Unit = t.printStackTrace()
  }
}
