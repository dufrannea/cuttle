package com.criteo.cuttle.platforms

import scala.concurrent.stm._

import cats._
import cats.implicits._

import io.circe._
import io.circe.syntax._

import org.http4s.circe._

import org.http4s._
import org.http4s.dsl.io._
import cats.effect._

/**
  * An execution pool backed by a priority queue. It limits the concurrent executions
  * and the priority queue is ordered by the [[scala.math.Ordering Ordering]] defined
  * on the [[com.criteo.cuttle.SchedulingContext SchedulingContext]].
  *
  * @param concurrencyLimit The maximum number of concurrent executions.
  */
case class ExecutionPool(concurrencyLimit: Int) extends WaitingExecutionQueue {
  def canRunNextCondition(implicit txn: InTxn) = _running().size < concurrencyLimit
  def doRunNext()(implicit txn: InTxn): Unit = ()

  override def routes(urlPrefix: String) = HttpRoutes.of[IO] {
      case GET -> Root / prefix if prefix == urlPrefix =>
        Ok(
          Json.obj(
            "concurrencyLimit" -> concurrencyLimit.asJson,
            "running" -> running.size.asJson,
            "waiting" -> waiting.size.asJson
          ))
    }.combineK(super.routes(urlPrefix))
}
