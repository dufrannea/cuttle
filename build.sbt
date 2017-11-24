val devMode = settingKey[Boolean]("Some build optimization are applied in devMode.")
val writeClasspath = taskKey[File]("Write the project classpath to a file.")

val VERSION = "0.2.2"

lazy val commonSettings = Seq(
  organization := "com.criteo.cuttle",
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
  // Maven config
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    "criteo-oss",
    sys.env.getOrElse("SONATYPE_PASSWORD", "")
  ),
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  pgpPassphrase := sys.env.get("SONATYPE_PASSWORD").map(_.toArray),
  pgpSecretRing := file(".travis/secring.gpg"),
  pgpPublicRing := file(".travis/pubring.gpg"),
  pomExtra in Global := {
    <url>https://github.com/criteo/cuttle</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      </license>
    </licenses>
    <scm>
      <connection>scm:git:github.com/criteo/cuttle.git</connection>
      <developerConnection>scm:git:git@github.com:criteo/cuttle.git</developerConnection>
      <url>github.com/criteo/cuttle</url>
    </scm>
    <developers>
      <developer>
        <name>Guillaume Bort</name>
        <email>g.bort@criteo.com</email>
        <url>https://github.com/guillaumebort</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Adrien Surée</name>
        <email>a.suree@criteo.com</email>
        <url>https://github.com/haveo</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Justin Coffey</name>
        <email>j.coffey@criteo.com</email>
        <url>https://github.com/jqcoffey</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Vincent Guerci</name>
        <email>v.guerci@criteo.com</email>
        <url>https://github.com/vguerci</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Alexandre Careil</name>
        <email>a.careil@criteo.com</email>
        <url>https://github.com/hhalex</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Arnaud Dufranne</name>
        <email>a.dufranne@criteo.com</email>
        <url>https://github.com/dufrannea</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Alexey Eryshev</name>
        <email>a.eryshev@criteo.com</email>
        <url>https://github.com/eryshev</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Jean-Philippe Lam Yee Mui</name>
        <email>jp.lamyeemui@criteo.com</email>
        <url>https://github.com/Masuzu</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
      <developer>
        <name>Jean-Baptiste Catté</name>
        <email>jb.catte@criteo.com</email>
        <url>https://github.com/jbkt</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
    </developers>
  },
  // Useful to run flakey tests
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

lazy val examples =
  (project in file("examples"))
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
    .settings(
      Option(System.getProperty("generateExamples"))
        .map(_ =>
          Seq(
            autoCompilerPlugins := true,
            addCompilerPlugin("com.criteo.socco" %% "socco-plugin" % "0.1.7"),
            scalacOptions := Seq(
              "-P:socco:out:examples/target/html",
              "-P:socco:package_com.criteo.cuttle:https://criteo.github.io/cuttle/api/"
            )
        ))
        .getOrElse(Nil): _*
    )
    .dependsOn(localdb)

lazy val root =
  (project in file("."))
    .enablePlugins(ScalaUnidocPlugin)
    .settings(commonSettings: _*)
    // .settings(
    //   publishArtifact := false,
    //   scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    //     Seq(
    //       "-sourcepath",
    //       baseDirectory.value.getAbsolutePath
    //     ),
    //     Opts.doc.title("cuttle"),
    //     Opts.doc.version(VERSION),
    //     Opts.doc.sourceUrl("https://github.com/criteo/cuttle/blob/master€{FILE_PATH}.scala"),
    //     Seq(
    //       "-doc-root-content",
    //       (baseDirectory.value / "core/src/main/scala/root.scala").getAbsolutePath
    //     )
    //   ).flatten
    // )
    .aggregate(examples, localdb)
