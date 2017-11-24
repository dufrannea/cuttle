val devMode = settingKey[Boolean]("Some build optimization are applied in devMode.")
val writeClasspath = taskKey[File]("Write the project classpath to a file.")

val VERSION = "0.2.2"

lazy val commonSettings = Seq(
  organization := "com.example",
  version := VERSION,
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq("2.11.11", "2.12.3"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Xfuture",
    "-Ywarn-unused",
    "-Ywarn-unused-import"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      Nil
      Seq(
        "-Ywarn-unused:-params"
      )
    case _ =>
      Nil
  }),
  devMode := Option(System.getProperty("devMode")).isDefined,
  writeClasspath := {
    val f = file(s"/tmp/classpath_${organization.value}.${name.value}")
    val classpath = (fullClasspath in Runtime).value
    IO.write(f, classpath.map(_.data).mkString(":"))
    streams.value.log.info(f.getAbsolutePath)
    f
  },
  commands += Command.single("repeat") { (state, arg) =>
    arg :: s"repeat $arg" :: state
  },
  // Run an example in another JVM, and quit on key press
  commands += Command.single("example") { (state, arg) =>
    s"/test:runMain com.criteo.cuttle.examples.TestExample $arg" :: state
  }
)

def removeDependencies(groups: String*)(xml: scala.xml.Node) = {
  import scala.xml._
  import scala.xml.transform._
  (new RuleTransformer(
    new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match {
        case dependency @ Elem(_, "dependency", _, _, _*) =>
          if (dependency.child.collect { case e: Elem => e }.headOption.exists { e =>
                groups.exists(group => e.toString == s"<groupId>$group</groupId>")
              }) Nil
          else dependency
        case x => x
      }
    }
  ))(xml)
}

lazy val localdb = {
  (project in file("localdb"))
    .settings(commonSettings: _*)
    .settings(
      publishArtifact := false,
      libraryDependencies ++= Seq(
        "com.wix" % "wix-embedded-mysql" % "2.1.4"
      )
    )
}

lazy val example =
  (project in file("example"))
    .settings(commonSettings: _*)
    .settings(
      publishArtifact := false,
      fork in Test := true,
      connectInput in Test := true,
      libraryDependencies ++= Seq(
        "com.criteo.cuttle" % "cuttle_2.11" % "0.2.2",
        "com.criteo.cuttle" % "timeseries_2.11" % "0.2.2"
      )
    )
    .dependsOn(localdb)

lazy val root =
  (project in file("."))
    .enablePlugins(ScalaUnidocPlugin)
    .settings(commonSettings: _*)
    .aggregate(example, localdb)
