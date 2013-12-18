import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {
  val appName         = "store"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    // Add your project dependencies here,
    "postgresql" % "postgresql" % "9.1-901.jdbc4",
    "com.typesafe" %% "play-plugins-mailer" % "2.1.0",
    jdbc,
    anorm,
    filters,
    "com.nulab-inc" %% "play2-oauth2-provider" % "0.3.0"
  )

  val main = play.Project(appName, appVersion, appDependencies).settings(
    scalacOptions ++= Seq("-feature")
  )
}
