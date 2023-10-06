ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "scala-3-macros-example",
    idePackagePrefix := Some("de.codecentric")
  )

lazy val macros = (project in file("macros"))
  .settings(
    name := "macros",
    idePackagePrefix := Some("de.codecentric"),
    scalacOptions := Seq("-Xcheck-macros")
  )

lazy val examples = (project in file("examples"))
  .settings(
    name := "examples",
    idePackagePrefix := Some("de.codecentric"),
    scalacOptions := Seq("-Xcheck-macros")
  )
  .dependsOn(macros)
