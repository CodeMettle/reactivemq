import SonatypeKeys._

// Metadata

organization := "com.codemettle.reactivemq"

name := "reactivemq"

description := "Akka-based ActiveMQ client"

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

crossScalaVersions := Seq("2.11.12", "2.12.5", "2.13.0")

scalaVersion := crossScalaVersions.value.last

scalacOptions ++= Seq("-unchecked", "-feature", "-deprecation")

scalacOptions += {
    CrossVersion partialVersion scalaVersion.value match {
        case Some((x, y)) if x >= 2 && y >= 12 => "-target:jvm-1.8"
        case _ => "-target:jvm-1.6"
    }
}

unmanagedSourceDirectories in Compile ++= {
    (unmanagedSourceDirectories in Compile).value.map { dir =>
        CrossVersion.partialVersion(scalaVersion.value) match {
            case Some((2, 13)) => file(dir.getPath ++ "-2.13+")
            case _             => file(dir.getPath ++ "-2.13-")
        }
    }
}

libraryDependencies ++= Seq(
    Deps.akkaActor % Provided,
    Deps.activemqClient % Provided
)

libraryDependencies ++= Seq(
    Deps.activemqBroker,
    Deps.activemqCamel,
    Deps.activemqJaas,
    Deps.akkaSlf,
    Deps.akkaTest,
    Deps.camel,
    Deps.jclOverSlf4j,
    Deps.logback,
    Deps.scalaTest
) map (_ % Test)

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

// Release

releasePublishArtifactsAction := PgpKeys.publishSigned.value
releaseCrossBuild := true

// Publish

xerial.sbt.Sonatype.sonatypeSettings

profileName := "com.codemettle"
