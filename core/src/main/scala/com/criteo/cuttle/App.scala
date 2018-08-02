package com.criteo.cuttle

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util._
import cats.Eq
import cats.data.{Kleisli, OptionT}
import cats.implicits._
import fs2.Stream
import io.circe._
import io.circe.syntax._
import io.circe.java8.time._
import com.criteo.cuttle.Auth._
import com.criteo.cuttle.ExecutionContexts.Implicits.serverExecutionContext
import com.criteo.cuttle.ExecutionContexts._
import com.criteo.cuttle.ExecutionStatus._
import com.criteo.cuttle.Metrics.{Gauge, Prometheus}
import com.criteo.cuttle.utils.getJVMUptime
import org.http4s.HttpRoutes
import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.server.ServerBuilder

private[cuttle] object App {

  val streamsOpen = new AtomicInteger(0)

  // TODO do not use default scheduler
  def sse[A](thunk: IO[Option[A]], encode: A => IO[Json])(implicit eqInstance: Eq[A]): IO[Response[IO]] = {
    import cats.effect._

    val stream: Stream[IO, ServerSentEvent] = (Stream.eval(IO{
      println(s"Opening - streams open ${streamsOpen.incrementAndGet()}")
    }).map(_ => ()) ++ Stream.sleep[IO](1.second))
      .evalMap(_ => IO.shift.flatMap(_ => thunk))
      .flatMap({
        case Some(x) => Stream(x)
        case None    => Stream.raiseError(new RuntimeException("Could not get result to stream"))
      })
      .changes
      .evalMap(r => IO(println("lol")) >> encode(r).map(r => {
        println(r)
        r
      }))
      .map(json => ServerSentEvent(json.noSpaces))
      .onFinalize(IO {
        println(s"Closing - streams still open ${streamsOpen.decrementAndGet()}")
      })

    Ok(stream)
  }

  implicit def projectEncoder[S <: Scheduling] = new Encoder[CuttleProject[S]] {
    override def apply(project: CuttleProject[S]) =
      Json.obj(
        "name" -> project.name.asJson,
        "version" -> Option(project.version).filterNot(_.isEmpty).asJson,
        "description" -> Option(project.description).filterNot(_.isEmpty).asJson,
        "env" -> Json.obj(
          "name" -> Option(project.env._1).filterNot(_.isEmpty).asJson,
          "critical" -> project.env._2.asJson
        )
      )
  }

  implicit lazy val executionLogEncoder: Encoder[ExecutionLog] = new Encoder[ExecutionLog] {
    override def apply(execution: ExecutionLog) =
      Json.obj(
        "id" -> execution.id.asJson,
        "job" -> execution.job.asJson,
        "startTime" -> execution.startTime.asJson,
        "endTime" -> execution.endTime.asJson,
        "context" -> execution.context,
        "status" -> (execution.status match {
          case ExecutionSuccessful => "successful"
          case ExecutionFailed     => "failed"
          case ExecutionRunning    => "running"
          case ExecutionWaiting    => "waiting"
          case ExecutionPaused     => "paused"
          case ExecutionThrottled  => "throttled"
          case ExecutionTodo       => "todo"
        }).asJson,
        "failing" -> execution.failing.map {
          case FailingJob(failedExecutions, nextRetry) =>
            Json.obj(
              "failedExecutions" -> Json.fromValues(failedExecutions.map(_.asJson(executionLogEncoder))),
              "nextRetry" -> nextRetry.asJson
            )
        }.asJson,
        "waitingSeconds" -> execution.waitingSeconds.asJson
      )
  }

  implicit val executionStatEncoder: Encoder[ExecutionStat] = new Encoder[ExecutionStat] {
    override def apply(execution: ExecutionStat): Json =
      Json.obj(
        "startTime" -> execution.startTime.asJson,
        "endTime" -> execution.endTime.asJson,
        "durationSeconds" -> execution.durationSeconds.asJson,
        "waitingSeconds" -> execution.waitingSeconds.asJson,
        "status" -> (execution.status match {
          case ExecutionSuccessful => "successful"
          case ExecutionFailed     => "failed"
          case ExecutionRunning    => "running"
          case ExecutionWaiting    => "waiting"
          case ExecutionPaused     => "paused"
          case ExecutionThrottled  => "throttled"
          case ExecutionTodo       => "todo"
        }).asJson
      )
  }

  implicit val tagEncoder = new Encoder[Tag] {
    override def apply(tag: Tag) =
      Json.obj(
        "name" -> tag.name.asJson,
        "description" -> Option(tag.description).filterNot(_.isEmpty).asJson
      )
  }

  implicit def jobEncoder[S <: Scheduling] = new Encoder[Job[S]] {
    override def apply(job: Job[S]) =
      Json
        .obj(
          "id" -> job.id.asJson,
          "name" -> Option(job.name).filterNot(_.isEmpty).getOrElse(job.id).asJson,
          "description" -> Option(job.description).filterNot(_.isEmpty).asJson,
          "scheduling" -> job.scheduling.toJson,
          "tags" -> job.tags.map(_.name).asJson
        )
        .asJson
  }

  implicit def workflowEncoder[S <: Scheduling] =
    new Encoder[Workflow[S]] {
      override def apply(workflow: Workflow[S]) = {
        val jobs = workflow.jobsInOrder.asJson
        val tags = workflow.vertices.flatMap(_.tags).asJson
        val dependencies = workflow.edges.map {
          case (to, from, _) =>
            Json.obj(
              "from" -> from.id.asJson,
              "to" -> to.id.asJson
            )
        }.asJson
        Json.obj(
          "jobs" -> jobs,
          "dependencies" -> dependencies,
          "tags" -> tags
        )
      }
    }

  import io.circe.generic.semiauto._
  implicit val encodeUser: Encoder[User] = deriveEncoder
  implicit val encodePausedJob: Encoder[PausedJob] = deriveEncoder
}

private[cuttle] case class App[S <: Scheduling](project: CuttleProject[S], executor: Executor[S], xa: XA) {
  import project.{scheduler, workflow}

  import App._

  private val allIds = workflow.vertices.map(_.id)

  private def parseJobIds(jobsQueryString: String): Set[String] =
    jobsQueryString.split(",").filter(_.trim().nonEmpty).toSet

  private def getJobsOrNotFound(jobsQueryString: String): Either[IO[Response[IO]], Set[Job[S]]] = {
    val jobsNames = parseJobIds(jobsQueryString)
    if (jobsNames.isEmpty) Right(workflow.vertices)
    else {
      val jobs = workflow.vertices.filter(v => jobsNames.contains(v.id))
      if (jobs.isEmpty) Left(NotFound())
      else Right(jobs)
    }
  }


  val publicApi: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root  / "status" =>
      val projectJson = (status: String) =>
        Json.obj(
          "project" -> project.name.asJson,
          "version" -> Option(project.version).filterNot(_.isEmpty).asJson,
          "status" -> status.asJson
      )
      executor.healthCheck() match {
        case Success(_) => Ok(projectJson("ok"))
        case _          => InternalServerError(projectJson("ko"))
      }
    case r@GET -> Root  / "statistics" =>
      val jobs = r.params.getOrElse("jobs", "")
      val events = r.params.getOrElse("events", "")
      val jobIds = parseJobIds(jobs)
      val ids = if (jobIds.isEmpty) allIds else jobIds

      def getStats: IO[Option[(Json, Json)]] =
        executor
          .getStats(ids)
          .map(
            stats =>
              Try(
                stats -> scheduler.getStats(ids)
              ).toOption)

      def asJson(x: (Json, Json)) = x match {
        case (executorStats, schedulerStats) =>
          executorStats.deepMerge(
            Json.obj(
              "scheduler" -> schedulerStats
            ))
      }

      events match {
        case "true" | "yes" =>
          sse(IO.suspend(getStats), (x: (Json, Json)) => IO(asJson(x)))
        case _ => getStats.>>=(_.map(stat => Ok(asJson(stat))).getOrElse(InternalServerError()))
      }

    case GET -> Root  / "statistics" / jobName =>
      executor
        .jobStatsForLastThirtyDays(jobName)
        .flatMap(stats => Ok(stats.asJson))

    case GET -> Root / "version" => Ok(project.version)

    case GET -> Root / "metrics" =>
      val metrics =
        executor.getMetrics(allIds, workflow) ++
          scheduler.getMetrics(allIds, workflow) :+
          Gauge("cuttle_jvm_uptime_seconds").labeled(("version", project.version), getJVMUptime)
      Ok(Prometheus.serialize(metrics))

    case r@GET -> Root  / "executions" / "status" / kind =>
      val jobs = r.params.getOrElse("jobs", "")
      val jobIds = parseJobIds(jobs)
      val limit = r.params.get("limit").flatMap(p => Try(p.toInt).toOption).getOrElse(25)
      val offset = r.params.get("offset").flatMap(p => Try(p.toInt).toOption).getOrElse(0)
      val order = r.params.getOrElse("order", "")
      val asc = order.toLowerCase == "asc"
      val sort = r.params.getOrElse("sort", "")
      val events = r.params.getOrElse("events", "")

      val ids = if (jobIds.isEmpty) allIds else jobIds

      def getExecutions: IO[Option[(Int, List[ExecutionLog])]] = kind match {
        case "started" =>
          IO(
            Some(
              executor.runningExecutionsSizeTotal(ids) -> executor
                .runningExecutions(ids, sort, asc, offset, limit)
                .toList))
        case "stuck" =>
          IO(
            Some(
              executor.failingExecutionsSize(ids) -> executor
                .failingExecutions(ids, sort, asc, offset, limit)
                .toList))
        case "paused" =>
          IO(
            Some(
              executor.pausedExecutionsSize(ids) -> executor
                .pausedExecutions(ids, sort, asc, offset, limit)
                .toList))
        case "finished" =>
          executor
            .archivedExecutionsSize(ids)
            .map(ids => Some(ids -> executor.allRunning.toList))
        case _ =>
          IO.pure(None)
      }

      def asJson(x: (Int, Seq[ExecutionLog])): IO[Json] = x match {
        case (total, executions) =>
          (kind match {
            case "finished" =>
              executor
                .archivedExecutions(scheduler.allContexts, ids, sort, asc, offset, limit, xa)
                .map(execs => execs.asJson)
            case _ =>
              IO(executions.asJson)
          }).map(
            data =>
              Json.obj(
                "total" -> total.asJson,
                "offset" -> offset.asJson,
                "limit" -> limit.asJson,
                "sort" -> sort.asJson,
                "asc" -> asc.asJson,
                "data" -> data
            ))
      }

      events match {
        case "true" | "yes" =>
          sse(getExecutions, asJson)
        case _ =>
          getExecutions.flatMap {
            _.map(e => asJson(e).flatMap(json => Ok(json)))
              .getOrElse(NotFound())
          }
      }

    case r@GET -> Root  / "executions" / id =>
      val events = r.params.getOrElse("events", "")
      def getExecution = IO.suspend(executor.getExecution(scheduler.allContexts, id))

      events match {
        case "true" | "yes" =>
          sse(getExecution, (e: ExecutionLog) => IO(e.asJson))
        case _ =>
          getExecution.flatMap(_.map(e => Ok(e.asJson)).getOrElse(NotFound()))
      }

    case req @ GET -> Root  / "executions" / id / streams =>
      lazy val streams = executor.openStreams(id)
      import org.http4s.headers._

      if (req.headers.get(org.http4s.headers.Accept).exists(_.values.contains(MediaType.`text/event-stream`))) {
        Ok(
          fs2.Stream(ServerSentEvent("BOS".asJson.toString())) ++
            streams
              .through(fs2.text.utf8Decode)
              .through(fs2.text.lines)
              .chunks
              .map(chunk => ServerSentEvent(Json.fromValues(chunk.toArray.toIterable.map(_.asJson)).toString())) ++
            fs2.Stream(ServerSentEvent("EOS".asJson.toString))
        )
      } else {
        Ok(streams, org.http4s.headers.`Content-Type`(MediaType.text.plain))
      }

    case GET -> Root  / "jobs" / "paused" =>
      Ok(executor.pausedJobs.asJson)

    case GET -> Root  / "project_definition" =>
      Ok(project.asJson)

    case GET -> Root  / "workflow_definition" =>
      Ok(workflow.asJson)
  }

  val privateApi: AuthedService[User, IO] = AuthedService {
    case POST -> Root  / "executions" / id / "cancel" as user =>
      executor.cancelExecution(id)(user)
      Ok()

    case POST -> Root  / "executions" / id / "force" / "success" as user =>
      executor.forceSuccess(id)(user)
      Ok()

    case r@POST -> Root  / "jobs" / "pause" as user =>
      val jobs = r.req.params.getOrElse("job", "")
      getJobsOrNotFound(jobs).fold(identity, jobs => {
        executor.pauseJobs(jobs)(user)
        Ok()
      })

    case r@POST -> Root  / "jobs" / "resume" as user =>
      val jobs = r.req.params.getOrElse("job", "")

      getJobsOrNotFound(jobs).fold(identity, jobs => {
        executor.resumeJobs(jobs)(user)
        Ok()
      })

    case POST -> Root  / "jobs" / "all" / "unpause" as user =>
      executor.resumeJobs(workflow.vertices)(user)
      Ok()

    case POST -> Root  / "jobs" / id / "unpause" as user =>
      workflow.vertices.find(_.id == id).fold(NotFound()) { job =>
        executor.resumeJobs(Set(job))(user)
        Ok()
      }

    case r@POST -> Root  / "executions" / "relaunch" as user =>
      val jobs = r.req.params.getOrElse("jobs", "")
      val filteredJobs = Try(jobs.split(",").toSeq.filter(_.nonEmpty)).toOption
        .filter(_.nonEmpty)
        .getOrElse(allIds)
        .toSet

      executor.relaunch(filteredJobs)(user)
      Ok()

    case req @ GET -> Root  / "shutdown" as user =>
      import scala.concurrent.duration._

      req.req.params.get("gracePeriodSeconds") match {
        case Some(s) =>
          Try(s.toLong) match {
            case Success(s) if s > 0 =>
              executor.gracefulShutdown(Duration(s, SECONDS))(user)
              Ok()
            case _ =>
              BadRequest("gracePeriodSeconds should be a positive integer")
          }
        case None =>
          req.req.params.get("hard") match {
            case Some(_) =>
              executor.hardShutdown()
              Ok()
            case None =>
              BadRequest("Either gracePeriodSeconds or hard should be specified as query parameter")
          }
     }
  }
  import cats.implicits._
  import org.http4s.implicits._

  val papi: HttpRoutes[IO] = project.authenticator(privateApi)

  val publicAssets: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / file => StaticFile.fromResource[IO](s"/public/$file").getOrElseF(NotFound())
  }

  val index: AuthedService[User, IO] = AuthedService[User, IO] {
    case _ =>  StaticFile.fromResource[IO](s"/public/index.html").getOrElseF(NotFound())
  }

  def r(b: ServerBuilder[IO]) : ServerBuilder[IO] = {
    b
      // scheduler api both authenticated and non authenticated
      .mountService(scheduler.publicRoutes(workflow, executor, xa) <+> publicApi <+> papi <+> project.authenticator(scheduler.privateRoutes(workflow, executor, xa)), "/api")
      // non authenticated public assets
      .mountService(publicAssets, "/public")
      // authenticated index
      .mountService(project.authenticator(index), "/")
  }
}
