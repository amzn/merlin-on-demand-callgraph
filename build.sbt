/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

import Dependencies._

ThisBuild / scalaVersion := "2.10.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.amazon"
ThisBuild / organizationName := "pvar"

lazy val root = (project in file("."))
  .settings(
    name := "merlin",
    libraryDependencies += scalaTest % Test
  )

// Add external JAR files shipped with TAJS or built by subodules
def thirdPartyJars(base: File): PathFinder =
  (base / "tajs_vr" / "lib") ** "*.jar" +++
    (base / "tajs_vr" / "extras" / "jalangilogger" / "build" / "libs") ** "*.jar" +++
    (base / "lib") ** "*.jar"

resolvers += "releases" at "https://maven.atlassian.com/repository/public/"
libraryDependencies += "com.atlassian.sourcemap" % "sourcemap" % "1.7.7"
libraryDependencies += "org.apache.commons" % "commons-csv" % "1.8"
// https://mvnrepository.com/artifact/com.google.guava/guava
libraryDependencies += "com.google.guava" % "guava" % "30.1-jre"

// https://mvnrepository.com/artifact/org.apache.commons/commons-text
libraryDependencies += "org.apache.commons" % "commons-text" % "1.9"

// https://mvnrepository.com/artifact/commons-io/commons-io
libraryDependencies += "commons-io" % "commons-io" % "2.6"

//https://mvnrepository.com/artifact/commons-cli/commons-cli
libraryDependencies += "commons-cli" % "commons-cli" % "1.4"

// https://mvnrepository.com/artifact/org.slf4j/slf4j-api/1.7.30
// Required dependency for synchronizedPDS and WPDS
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.30"

Compile / run / mainClass := Some(
  "com.amazon.pvar.tspoc.merlin.experiments.Main"
)

Compile / unmanagedJars := thirdPartyJars(baseDirectory.value).get.classpath
Compile / unmanagedSourceDirectories += baseDirectory.value / "tajs_vr" / "src"
Compile / unmanagedSourceDirectories += baseDirectory.value / "tajs_vr" / "src-forwards-backwards-api"
Compile / unmanagedSourceDirectories += baseDirectory.value / "tajs_vr" / "extras" / "inspector" / "src"
Compile / unmanagedResourceDirectories += baseDirectory.value / "tajs_vr" / "resources"
Test / parallelExecution := false

console / initialCommands :=
  """
    |import com.amazon.pvar.merlin._
    |""".stripMargin

libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test

Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
