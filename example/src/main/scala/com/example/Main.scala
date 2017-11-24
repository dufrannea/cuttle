package com.example
import com.criteo.cuttle._
import com.criteo.cuttle.platforms.local._
import com.criteo.cuttle.timeseries._
import java.time.ZoneOffset.UTC
import java.time._

import scala.concurrent.duration._

object Main {
 def main(args: Array[String]): Unit = {
    val start: Instant = LocalDate.now.minusDays(7).atStartOfDay.toInstant(UTC)
    val hello1 =
      Job("hello1", hourly(start), "Hello 1") {
        implicit e =>
          e.streams.info(s"Hey how are you")
          e.park(1.seconds).map(_ => Completed)
      }

    val hello2 = Job("hello2", hourly(start), "Hello 2") { implicit e =>
      exec"""sh -c '
         |    echo Looping for 20 seconds...
         |    for i in `seq 1 20`
         |    do
         |        date
         |        sleep 1
         |    done
         |    echo Ok
         |'""" ()
    }

    val world = Job("world", daily(UTC, start), "World") { implicit e =>
      e.streams.info("World!")
      for {
        _ <- e.park(3.seconds)
        completed <- exec"sleep 3" ()
      } yield completed
    }

    CuttleProject("Hello World", env = ("Demo", false)) {
      world dependsOn (hello1 and hello2)
    }.
    start()
  }
}
