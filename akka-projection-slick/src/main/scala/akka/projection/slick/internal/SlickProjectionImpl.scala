/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.slick.internal

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import akka.Done
import akka.actor.ClassicActorSystemProvider
import akka.annotation.InternalApi
import akka.event.Logging
import akka.pattern._
import akka.projection.Projection
import akka.projection.ProjectionId
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.Fail
import akka.projection.slick.RetryAndFail
import akka.projection.slick.RetryAndSkip
import akka.projection.slick.Skip
import akka.projection.slick.SlickEventHandler
import akka.stream.KillSwitches
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

@InternalApi
private[projection] object SlickProjectionImpl {
  sealed trait Strategy
  case object ExactlyOnce extends Strategy
  final case class AtLeastOnce(afterEnvelopes: Int, orAfterDuration: FiniteDuration) extends Strategy
}

@InternalApi
private[projection] class SlickProjectionImpl[Offset, Envelope, P <: JdbcProfile](
    val projectionId: ProjectionId,
    sourceProvider: SourceProvider[Offset, Envelope],
    databaseConfig: DatabaseConfig[P],
    strategy: SlickProjectionImpl.Strategy,
    eventHandler: SlickEventHandler[Envelope])
    extends Projection[Envelope] {
  import SlickProjectionImpl._

  private val offsetStore = new SlickOffsetStore(databaseConfig.db, databaseConfig.profile)

  private val killSwitch = KillSwitches.shared(projectionId.id)
  private val promiseToStop: Promise[Done] = Promise()

  private val started = new AtomicBoolean(false)

  override def run()(implicit systemProvider: ClassicActorSystemProvider): Unit = {
    if (started.compareAndSet(false, true)) {
      val done = mappedSource().runWith(Sink.ignore)
      promiseToStop.completeWith(done)
    }
  }

  override def stop()(implicit ec: ExecutionContext): Future[Done] = {
    if (started.get()) {
      killSwitch.shutdown()
      promiseToStop.future
    } else {
      Future.failed(new IllegalStateException(s"Projection [$projectionId] not started yet!"))
    }
  }

  /**
   * INTERNAL API
   *
   * This method returns the projection Source mapped with `processEnvelope`, but before any sink attached.
   * This is mainly intended to be used by the TestKit allowing it to attach a TestSink to it.
   */
  override private[projection] def mappedSource()(
      implicit systemProvider: ClassicActorSystemProvider): Source[Done, _] = {
    implicit val dispatcher: ExecutionContext = systemProvider.classicSystem.dispatcher

    // TODO: add a LogSource for projection when we have a name and key
    val akkaLogger = Logging(systemProvider.classicSystem, this.getClass)

    val lastKnownOffset: Future[Option[Offset]] = offsetStore.readOffset(projectionId)

    val futSource = lastKnownOffset.map { offsetOpt =>
      akkaLogger.debug("Starting projection [{}] from offset [{}]", projectionId, offsetOpt)
      sourceProvider.source(offsetOpt)
    }

    val handlerFlow: Flow[Envelope, Done, _] =
      strategy match {
        case ExactlyOnce =>
          Flow[Envelope]
            .mapAsync(1)(processEnvelopeAndStoreOffsetInSameTransaction)

        case AtLeastOnce(1, _) =>
          Flow[Envelope]
            .mapAsync(1)(processEnvelopeAndStoreOffsetInSeparateTransactions)

        case AtLeastOnce(afterEnvelopes, orAfterDuration) =>
          Flow[Envelope]
            .mapAsync(1) { env =>
              processEnvelope(env).map(_ => sourceProvider.extractOffset(env))
            }
            .groupedWithin(afterEnvelopes, orAfterDuration)
            .collect { case grouped if grouped.nonEmpty => grouped.last }
            .mapAsync(parallelism = 1)(storeOffset)
      }

    Source
      .futureSource(futSource)
      .via(killSwitch.flow)
      .via(handlerFlow)
  }

  private val futDone = Future.successful(Done)

  private def applyUserRecovery(envelope: Envelope)(futureCallback: () => Future[Done])(
      implicit systemProvider: ClassicActorSystemProvider) = {

    implicit val scheduler = systemProvider.classicSystem.scheduler
    implicit val dispatcher = systemProvider.classicSystem.dispatcher

    // this will count as one attempt
    val firstAttempt = futureCallback()

    firstAttempt.recoverWith {
      case NonFatal(err) =>
        eventHandler.onFailure(envelope, err) match {
          case Fail                         => firstAttempt
          case Skip                         => futDone
          case RetryAndFail(retries, delay) =>
            // less one attempt
            retry(futureCallback, retries - 1, delay)
          case RetryAndSkip(retries, delay) =>
            // less one attempt
            retry(futureCallback, retries - 1, delay).recoverWith {
              case NonFatal(_) => futDone
            }
        }

    }
  }

  private def processEnvelopeAndStoreOffsetInSameTransaction(env: Envelope)(
      implicit systemProvider: ClassicActorSystemProvider): Future[Done] = {

    import databaseConfig.profile.api._
    implicit val dispatcher = systemProvider.classicSystem.dispatcher

    // run user function and offset storage on the same transaction
    // any side-effect in user function is at-least-once
    val txDBIO =
      offsetStore
        .saveOffset(projectionId, sourceProvider.extractOffset(env))
        .flatMap(_ => eventHandler.handleEvent(env))
        .transactionally

    applyUserRecovery(env) { () =>
      databaseConfig.db.run(txDBIO).map(_ => Done)
    }
  }

  private def processEnvelopeAndStoreOffsetInSeparateTransactions(env: Envelope)(
      implicit systemProvider: ClassicActorSystemProvider): Future[Done] = {

    import databaseConfig.profile.api._
    implicit val dispatcher = systemProvider.classicSystem.dispatcher

    // user function in one transaction (may be composed of several DBIOAction), and offset save in separate
    val dbio =
      eventHandler
        .handleEvent(env)
        .transactionally
        .flatMap(_ => offsetStore.saveOffset(projectionId, sourceProvider.extractOffset(env)))

    applyUserRecovery(env) { () =>
      databaseConfig.db.run(dbio).map(_ => Done)
    }
  }

  private def processEnvelope(env: Envelope)(implicit systemProvider: ClassicActorSystemProvider): Future[Done] = {

    import databaseConfig.profile.api._
    implicit val dispatcher = systemProvider.classicSystem.dispatcher

    // user function in one transaction (may be composed of several DBIOAction)
    val dbio = eventHandler.handleEvent(env).transactionally
    applyUserRecovery(env) { () =>
      databaseConfig.db.run(dbio).map(_ => Done)
    }
  }

  private def storeOffset(offset: Offset)(implicit ec: ExecutionContext): Future[Done] = {
    // only one DBIOAction, no need for transactionally
    val dbio = offsetStore.saveOffset(projectionId, offset)
    databaseConfig.db.run(dbio).map(_ => Done)
  }
}
