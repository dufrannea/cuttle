package com.criteo.cuttle;
package timeseries;

import java.time.ZoneOffset.UTC
import java.time.{Instant, LocalDate}

import ch.vorburger.mariadb4j._

import scala.concurrent.duration._

import com.criteo.cuttle._

object HelloTimeSeries {


  // A cuttle project is just embeded into any Scala application.
  def main(args: Array[String]): Unit = {
    LocalDB.spawn()

    // We define a common start date for all our jobs. This is required by the
    // time series scheduler to define a start date for each job. Here we dynaically
    // compute it as 7 days ago (_and we round it to midnight UTC_).
    val start: Instant = LocalDate.now.minusDays(7).atStartOfDay.toInstant(UTC)

    implicit val logger = new Logger {
      def logMe(message: => String, level: String) = println(s"${java.time.Instant.now}\t${level}\t${message}")
      override def info(message: => String): Unit = logMe(message, "INFO")
      override def debug(message: => String): Unit = logMe(message, "DEBUG")
      override def warn(message: => String): Unit = logMe(message, "WARN")
      override def error(message: => String): Unit = logMe(message, "ERROR")
      override def trace(message: => String): Unit = ()
    }

    // Here is our first job. The second parameter is the scheduling configuration.
    // __hello1__ is defined as a job computing hourly partitions starting at the start
    // date declared before.
    val hello1 =
      Job("hello1", hourly(start), "Hello 1", tags = Set(Tag("hello"))) {
        // The side effect function takes the execution as parameter. The execution
        // contains useful meta data as well as the __context__ which is basically the
        // input data for our execution.
        implicit e =>
          // Because this job uses a time series scheduling configuration the context
          // contains the information about the time partition to compute, ie the start
          // and end date.
          val partitionToCompute = (e.context.start) + "-" + (e.context.end)

          e.streams.info(s"Hello 1 for $partitionToCompute")
          e.streams.info("Check my project page at https://github.com/criteo/cuttle")
          e.streams.info("Do it quickly! I will wait you here for 1 second")
          e.park(1.seconds).map(_ => Completed)
      }

    // Finally we bootstrap our cuttle project.
    CuttleProject("Hello World", version = "123", env = ("dev", false)) {
      // Any cuttle project contains a Workflow to execute. This Workflow is composed from jobs
      // or from others smaller Workflows.
      val lol: Workflow = hello1
      lol dependsOn(lol -> TimeSeriesDependency(java.time.Duration.ofHours(-1), java.time.Duration.ofHours(-1)))
    }.
    // Starts the scheduler and an HTTP server.
    start(
      logsRetention = Some(1.hour),
      databaseConfig = DatabaseConfig(Seq(DBLocation("localhost", 3388)),
      "cuttle_dev",
      "cuttle_dev",
      "cuttle_dev"))
  }
}



object LocalDB {
  def spawn(): Unit = {
    val config = DBConfigurationBuilder.newBuilder()
    config.setPort(3388)
    config.setDatabaseVersion("mariadb-10.2.11")
    val db = DB.newEmbeddedDB(config.build)
    db.start()
    db.createDB("cuttle_dev")
    println("started!")
    println("if needed you can connect to this running db using:")
    println("> mysql -u root -h 127.0.0.1 -P 3388 cuttle_dev")
    println("press [Ctrl+D] to stop...")
//    while (System.in.read != -1) ()
//    db.stop()
  }
}

