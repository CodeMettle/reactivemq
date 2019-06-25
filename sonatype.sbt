sonatypeProfileName := "com.codemettle"

publishMavenStyle := true

licenses += ("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

import xerial.sbt.Sonatype._

sonatypeProjectHosting := Some(GitHubHosting("CodeMettle", "reactivemq", "Steven Scott", "steven@codemettle.com"))

scmInfo := Some(
  ScmInfo(url("https://github.com/CodeMettle/reactivemq"), "scm:git:https://github.com/CodeMettle/reactivemq.git",
    Some("scm:git:git@github.com:CodeMettle/reactivemq.git")))

developers := List(
  Developer("codingismy11to7", "Steven Scott", "steven@codemettle.com", url("https://github.com/codingismy11to7")),
)
