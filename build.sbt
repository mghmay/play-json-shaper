ThisBuild / organization       := "io.github.mghmay"
ThisBuild / organizationName   := "Mathew May"
ThisBuild / startYear          := Some(2025)
ThisBuild / scalaVersion       := "2.13.16"
ThisBuild / crossScalaVersions := Seq("2.13.16", "3.3.4")
ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / licenses           := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage           := Some(url("https://github.com/mghmay/play-json-shaper"))
ThisBuild / scmInfo            := Some(
  ScmInfo(
    url("https://github.com/mghmay/play-json-shaper"),
    "scm:git@github.com:mghmay/play-json-shaper.git"
  )
)
ThisBuild / coverageMinimumBranchTotal       := 100
ThisBuild / coverageMinimumStmtTotal       := 100
ThisBuild / coverageFailOnMinimum := true
ThisBuild / coverageHighlighting  := true

headerLicenseStyle             := HeaderLicenseStyle.SpdxSyntax
Compile / headerSources ++= (Compile / sources).value
Test / headerSources ++= (Test / sources).value

lazy val root = (project in file("."))
  .settings(
    name                     := "play-json-shaper",
    libraryDependencies ++= Seq(
      "org.playframework" %% "play-json"     % "3.0.4",
      "org.scalatest"     %% "scalatest"     % "3.2.19" % Test,
      "org.scalacheck"    %% "scalacheck"    % "1.19.0" % Test,
    ),
    Test / parallelExecution := false
  )
