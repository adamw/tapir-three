lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.demo",
  scalaVersion := "2.12.8"
)

val tapirVersion = "0.7.6"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, name := "root")
  .aggregate(core)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.tapir" %% "tapir-akka-http-server" % tapirVersion,
      "com.softwaremill.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.tapir" %% "tapir-openapi-docs" % tapirVersion,
      "com.softwaremill.tapir" %% "tapir-openapi-circe-yaml" % tapirVersion,
      "com.softwaremill.tapir" %% "tapir-sttp-client" % tapirVersion,
      "org.webjars" % "swagger-ui" % "3.22.0"
    )
  )

