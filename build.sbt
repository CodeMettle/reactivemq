import SonatypeKeys._

// Metadata

organization := "com.codemettle.reactivemq"

name := "reactivemq"

version := "0.5.1-SNAPSHOT"

description := "Solr HTTP client using Akka and Spray"

startYear := Some(2015)

homepage := Some(url("https://github.com/CodeMettle/reactivemq"))

organizationName := "CodeMettle, LLC"

organizationHomepage := Some(url("http://www.codemettle.com"))

licenses += ("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scmInfo := Some(
    ScmInfo(url("https://github.com/CodeMettle/reactivemq"), "scm:git:https://github.com/CodeMettle/reactivemq.git",
        Some("scm:git:git@github.com:CodeMettle/reactivemq.git")))

pomExtra := {
    <developers>
        <developer>
            <name>Steven Scott</name>
            <email>steven@codemettle.com</email>
            <url>https://github.com/codingismy11to7/</url>
        </developer>
    </developers>
}

// Build

crossScalaVersions := Seq("2.10.4", "2.11.5")

scalaVersion := crossScalaVersions.value.last

scalacOptions ++= Seq("-unchecked", "-feature", "-deprecation")

libraryDependencies ++= Seq(
    Deps.akkaActor % Provided,
    Deps.activemqClient % Provided,
    Deps.sprayUtil
)

libraryDependencies ++= Seq(
    Deps.activemqBroker,
    Deps.activemqCamel,
    Deps.akkaCamel,
    Deps.akkaSlf,
    Deps.akkaTest,
    Deps.jclOverSlf4j,
    Deps.logback,
    Deps.scalaTest
) map (_ % Test)

//libraryDependencies += {
//    CrossVersion partialVersion scalaVersion.value match {
//        case Some((2, 10)) => Deps.ficus2_10
//        case Some((2, 11)) => Deps.ficus2_11
//        case _ => sys.error("Ficus dependency needs updating")
//    }
//} % Test
//
//publishArtifact in Test := true

autoAPIMappings := true

apiMappings ++= {
    val cp: Seq[Attributed[File]] = (fullClasspath in Compile).value
    def findManagedDependency(moduleId: ModuleID): File = {
        ( for {
            entry <- cp
            module <- entry.get(moduleID.key)
            if module.organization == moduleId.organization
            if module.name startsWith moduleId.name
            jarFile = entry.data
        } yield jarFile
            ).head
    }
    Map(
        findManagedDependency("org.scala-lang" % "scala-library" % scalaVersion.value) -> url(s"http://www.scala-lang.org/api/${scalaVersion.value}/"),
        findManagedDependency(Deps.akkaActor) -> url(s"http://doc.akka.io/api/akka/${Versions.akka}/")
    )
}

// Publish

xerial.sbt.Sonatype.sonatypeSettings

profileName := "com.codemettle"
