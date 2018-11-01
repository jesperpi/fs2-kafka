/*
 * Copyright 2018 OVO Energy Ltd
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

package fs2.kafka

import cats.data.NonEmptyList
import cats.effect.concurrent.{Deferred, MVar, Ref}
import cats.effect.syntax.concurrent._
import cats.effect.{ConcurrentEffect, Fiber, Timer, _}
import cats.instances.list._
import cats.instances.unit._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.monad._
import cats.syntax.monadError._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import fs2.concurrent.Queue
import fs2.kafka.KafkaConsumerActor.Request._
import fs2.kafka.KafkaConsumerActor._
import fs2.kafka.internal.syntax._
import fs2.{Chunk, Stream}
import org.apache.kafka.clients.consumer.{KafkaConsumer => KConsumer, _}
import org.apache.kafka.common.TopicPartition

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

abstract class KafkaConsumer[F[_], K, V] {
  def stream: Stream[F, CommittableMessage[F, K, V]]

  def partitionedStream: Stream[F, Stream[F, CommittableMessage[F, K, V]]]

  def subscribe(topics: NonEmptyList[String]): Stream[F, Unit]

  def fiber: Fiber[F, Unit]
}

private[kafka] object KafkaConsumer {
  private[this] def createConsumer[F[_], K, V](
    settings: ConsumerSettings[K, V]
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, MVar[F, Consumer[K, V]]] =
    Resource.make[F, MVar[F, Consumer[K, V]]] {
      F.delay {
          new KConsumer(
            (settings.properties: Map[String, AnyRef]).asJava,
            settings.keyDeserializer,
            settings.valueDeserializer
          )
        }
        .flatMap(MVar[F].of)
    } { mVar =>
      mVar.lease { consumer =>
        context.evalOn(settings.executionContext) {
          F.delay(consumer.close(settings.closeTimeout.asJava))
        }
      }
    }

  private[this] def startConsumerActor[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    polls: Queue[F, Request[F, K, V]],
    actor: KafkaConsumerActor[F, K, V],
    running: F[Boolean]
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      requests.tryDequeue1
        .flatMap(_.map(F.pure).getOrElse(polls.dequeue1))
        .flatMap(actor.handle(_) >> context.shift)
        .whileM_(running)
        .start
    }(_.cancel)

  private[this] def startPollScheduler[F[_], K, V](
    polls: Queue[F, Request[F, K, V]],
    pollInterval: FiniteDuration,
    running: F[Boolean]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      {
        polls.enqueue1(Poll()) >>
          timer.sleep(pollInterval)
      }.whileM_(running).start
    }(_.cancel)

  private[this] def createKafkaConsumer[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    ref: Ref[F, State[F, K, V]],
    actor: Fiber[F, Unit],
    polls: Fiber[F, Unit]
  )(implicit F: Concurrent[F]): KafkaConsumer[F, K, V] =
    new KafkaConsumer[F, K, V] {
      override val fiber: Fiber[F, Unit] = {
        val requestShutdown = requests.enqueue1(Shutdown())
        val forceShutdown = ref.update(_.asShutdown)

        val join = {
          val actorFiber = Fiber(actor.join, requestShutdown)
          val pollsFiber = Fiber(polls.join, forceShutdown)
          (actorFiber combine pollsFiber).join
        }

        Fiber(join, requestShutdown)
      }

      private val assignment: F[Set[TopicPartition]] =
        Deferred[F, Either[Throwable, Set[TopicPartition]]].flatMap { deferred =>
          val assignment = requests.enqueue1(Assignment(deferred)) >> deferred.get.rethrow
          F.race(fiber.join, assignment).map {
            case Left(())        => Set.empty
            case Right(assigned) => assigned
          }
        }

      private def requestPartitions(
        assigned: Set[TopicPartition]
      ): F[Stream[F, CommittableMessage[F, K, V]]] =
        Queue
          .bounded[F, Option[Chunk[CommittableMessage[F, K, V]]]](assigned.size)
          .flatMap { queue =>
            assigned.toList
              .traverse { partition =>
                Deferred[F, Chunk[CommittableMessage[F, K, V]]].flatMap { deferred =>
                  val fetch = requests.enqueue1(Fetch(partition, deferred)) >> deferred.get
                  F.race(fiber.join, fetch).flatMap {
                    case Left(()) => F.unit
                    case Right(chunk) =>
                      if (chunk.nonEmpty)
                        queue.enqueue1(Some(chunk))
                      else F.unit
                  }
                }.start
              }
              .flatMap(_.combineAll.join)
              .flatMap(_ => queue.enqueue1(None))
              .start
              .as {
                queue.dequeue.unNoneTerminate
                  .flatMap(Stream.chunk)
                  .covary[F]
              }
          }

      private val empty: F[Stream[F, CommittableMessage[F, K, V]]] =
        F.pure(Stream.empty.covaryAll[F, CommittableMessage[F, K, V]])

      override val partitionedStream: Stream[F, Stream[F, CommittableMessage[F, K, V]]] =
        Stream
          .repeatEval {
            assignment.flatMap { assigned =>
              if (assigned.isEmpty) empty
              else requestPartitions(assigned)
            }
          }
          .interruptWhen(fiber.join.attempt)

      override val stream: Stream[F, CommittableMessage[F, K, V]] =
        partitionedStream.flatten

      override def subscribe(topics: NonEmptyList[String]): Stream[F, Unit] =
        Stream.eval(requests.enqueue1(Subscribe(topics)))

      override def toString: String =
        "KafkaConsumer$" + System.identityHashCode(this)
    }

  def consumerResource[F[_], K, V](
    settings: ConsumerSettings[K, V]
  )(
    implicit F: ConcurrentEffect[F],
    context: ContextShift[F],
    timer: Timer[F]
  ): Resource[F, KafkaConsumer[F, K, V]] =
    Resource.liftF(Queue.unbounded[F, Request[F, K, V]]).flatMap { requests =>
      Resource.liftF(Queue.bounded[F, Request[F, K, V]](1)).flatMap { polls =>
        Resource.liftF(Ref.of[F, State[F, K, V]](State.empty)).flatMap { ref =>
          createConsumer(settings).flatMap { mVar =>
            val running = ref.get.map(_.running)
            val actor = new KafkaConsumerActor(settings, ref, requests, mVar)
            startConsumerActor(requests, polls, actor, running).flatMap { actor =>
              startPollScheduler(polls, settings.pollInterval, running).map { polls =>
                createKafkaConsumer(requests, ref, actor, polls)
              }
            }
          }
        }
      }
    }
}
