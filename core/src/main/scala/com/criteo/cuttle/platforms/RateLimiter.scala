package com.criteo.cuttle.platforms

import scala.concurrent.stm._
import scala.concurrent.duration._
import java.time._

import io.circe._
import io.circe.syntax._
import io.circe.java8.time._

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe._

import cats.effect._
import fs2._
import org.http4s._
import org.http4s.implicits._
import com.criteo.cuttle.utils

import scala.concurrent.ExecutionContext

private[cuttle] object RateLimiter {
  implicit val SC: ExecutionContext = utils.createExecutionContext("com.criteo.cuttle.platforms.RateLimiter.SC")
}

/**
  * An rate limiter pool backed by a priority queue. It rate limits the executions
  * and the priority queue is ordered by the [[scala.math.Ordering Ordering]] defined
  * on the [[com.criteo.cuttle.SchedulingContext SchedulingContext]].
  *
  * The implementation is based on the tokens bucket algorithm.
  *
  * @param tokens Maximum (and initial) number of tokens.
  * @param refillRateInMs A token is added to the bucket every `refillRateInMs` milliseconds.
  */
class RateLimiter(tokens: Int, refillRateInMs: Int) extends WaitingExecutionQueue {
  private val _tokens = Ref(tokens)
  private val _lastRefill = Ref(Instant.now)

  import cats._
  import cats.implicits._

  import cats.effect._
  import cats.effect.implicits._
  import RateLimiter.SC

  fs2.Stream.awakeEvery[IO](refillRateInMs.milliseconds)
    .flatMap(_ => {
      atomic { implicit txn =>
        if (_tokens() < tokens) {
          _tokens() = _tokens() + 1
          _lastRefill() = Instant.now
        }
      }
      fs2.Stream(runNext())
    })
    .compile
    .drain
    .unsafeRunAsync(_ => ())

  def canRunNextCondition(implicit txn: InTxn) = _tokens() >= 1
  def doRunNext()(implicit txn: InTxn) = _tokens() = _tokens() - 1

  override def routes(urlPrefix: String) = HttpRoutes.of[IO] {
      case GET -> Root / prefix if prefix == urlPrefix =>
        Ok(
          Json.obj(
            "max_tokens" -> tokens.asJson,
            "available_tokens" -> _tokens.single.get.asJson,
            "refill_rate_in_ms" -> refillRateInMs.asJson,
            "last_refill" -> _lastRefill.single.get.asJson
          ))
    }.combineK(super.routes(urlPrefix))

}
