package scala.build

import java.nio.file.Paths

import sbt._
import Keys._
import sbt.complete.Parsers._

import BuildSettings.autoImport._
import VersionUtil._

/** Custom commands for use by the Jenkins scripts. This keeps the surface area and call syntax small. */
object ScriptCommands {
  def env(key: String) = Option(System.getenv(key)).getOrElse("")

  def all = Seq(
    setupPublishCoreNonOpt,
    setupPublishCore,
    setupValidateTest,
    setupBootstrapStarr, setupBootstrapLocker, setupBootstrapQuick, setupBootstrapPublish,
    enableOptimizerCommand,
    restarr, restarrFull,
  )

  /** Set up the environment for `validate/publish-core`.
   * The optional argument is the Artifactory snapshot repository URL. */
  def setupPublishCoreNonOpt = setup("setupPublishCoreNonOpt") { args =>
    Seq(
      baseVersionSuffix in Global := "SHA-SNAPSHOT"
    ) ++ (args match {
      case Seq(url) => publishTarget(url)
      case Nil => Nil
    }) ++ noDocs
  }

  /** Set up the environment for `validate/publish-core`.
    * The optional argument is the Artifactory snapshot repository URL. */
  def setupPublishCore = setup("setupPublishCore") { args =>
    Seq(
      baseVersionSuffix in Global := "SHA-SNAPSHOT"
    ) ++ (args match {
      case Seq(url) => publishTarget(url)
      case Nil => Nil
    }) ++ noDocs ++ enableOptimizer
  }

  /** Set up the environment for `validate/test`.
    * The optional argument is the Artifactory snapshot repository URL. */
  def setupValidateTest = setup("setupValidateTest") { args =>
    Seq(
      testOptions in IntegrationTest in LocalProject("test") ++= Seq(Tests.Argument("--show-log"), Tests.Argument("--show-diff"))
    ) ++ (args match {
      case Seq(url) => Seq(resolvers in Global += "scala-pr" at url)
      case Nil => Nil
    }) ++ enableOptimizer
  }

  /** Set up the environment for building STARR in `validate/bootstrap`. The arguments are:
    * - Repository URL for publishing
    * - Version number to publish */
  def setupBootstrapStarr = setup("setupBootstrapStarr") { case Seq(fileOrUrl, ver) =>
    val url = fileToUrl(fileOrUrl)
    Seq(
      baseVersion in Global := ver,
      baseVersionSuffix in Global := "SPLIT"
    ) ++ publishTarget(url) ++ noDocs ++ enableOptimizer
  }

  /** Set up the environment for building locker in `validate/bootstrap`. The arguments are:
    * - Repository file or URL for publishing locker and resolving STARR
    * - Version number to publish */
  def setupBootstrapLocker = setup("setupBootstrapLocker") { case Seq(fileOrUrl, ver) =>
    val url = fileToUrl(fileOrUrl)
    Seq(
      baseVersion in Global := ver,
      baseVersionSuffix in Global := "SPLIT",
      resolvers in Global += "scala-pr" at url
    ) ++ publishTarget(url) ++ noDocs ++ enableOptimizer
  }

  /** Set up the environment for building quick in `validate/bootstrap`. The arguments are:
    * - Repository URL for publishing
    * - Version number to publish
    * - Optional: Repository for resolving (same as repository for publishing if not specified)
    * Note that the artifacts produced here are consumed by scala-dist, so the docs have to be built.
    */
  def setupBootstrapQuick = setup("setupBootstrapQuick") { case Seq(targetFileOrUrl, ver, resolverFileOrUrl) =>
    val targetUrl = fileToUrl(targetFileOrUrl)
    val resolverUrl = fileToUrl(resolverFileOrUrl)
    Seq(
      baseVersion in Global := ver,
      baseVersionSuffix in Global := "SPLIT",
      resolvers in Global += "scala-pr" at resolverUrl,
      testOptions in IntegrationTest in LocalProject("test") ++= Seq(Tests.Argument("--show-log"), Tests.Argument("--show-diff"))
    ) ++ publishTarget(targetUrl) ++ enableOptimizer
  }

  /** Set up the environment for publishing in `validate/bootstrap`. The arguments are:
    * - Temporary bootstrap repository URL for resolving modules
    * - Version number to publish
    * All artifacts are published to Sonatype. */
  def setupBootstrapPublish = setup("setupBootstrapPublish") { case Seq(fileOrUrl, ver) =>
    val url = fileToUrl(fileOrUrl)
    Seq(
      baseVersion in Global := ver,
      baseVersionSuffix in Global := "SPLIT",
      resolvers in Global += "scala-pr" at url,
      publishTo in Global := Some("sonatype-releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"),
      credentials in Global += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", env("SONA_USER"), env("SONA_PASS"))
      // pgpSigningKey and pgpPassphrase are set externally by travis / the bootstrap script, as the sbt-pgp plugin is not enabled by default
    ) ++ enableOptimizer
  }

  def enableOptimizerCommand = setup("enableOptimizer")(_ => enableOptimizer)

  /** For local dev: sets `scalaVersion` to the version in `/buildcharacter.properties` or the given arg.
   * Running `reload` will re-read the build files, resetting `scalaVersion`. */
  def restarr = Command("restarr")(_ => (Space ~> StringBasic).?) { (state, s) =>
    val newVersion = s.getOrElse(readVersionFromPropsFile(state))
    val x = Project.extract(state)
    val sv = x.get(Global / scalaVersion)
    state.log.info(s"Re-STARR'ing: setting scalaVersion from $sv to $newVersion (`reload` to undo)")
    x.appendWithSession(Global / scalaVersion := newVersion, state) // don't use version.value or it'll be a wrong, new value
  }

  /** For local dev: publishes locally (without optimizing) & then sets the new `scalaVersion`.
   * Also it generates `/buildcharacter.properties` which is the default used by `restarr`. */
  def restarrFull = Command.command("restarrFull") { state =>
    setupPublishCoreNonOpt.nameOption.get ::
        generateBuildCharacterPropertiesFile.key.label ::
        publishLocal.key.label ::
        restarr.nameOption.get ::
        state
  }

  private def readVersionFromPropsFile(state: State): String = {
    val props = readProps(file("buildcharacter.properties"))
    val newVersion = props("maven.version.number")
    val fullVersion = props("version.number")
    state.log.info(s"Read STARR version from buildcharacter.properties: $newVersion (full version: $fullVersion)")
    newVersion
  }

  private[this] def setup(name: String)(f: Seq[String] => Seq[Setting[_]]) = Command.args(name, name) { case (state, seq) =>
    Project.extract(state).appendWithSession(f(seq), state)
  }

  val enableOptimizer = Seq(
    scalacOptions in Compile in ThisBuild ++= Seq("-opt:l:inline", "-opt-inline-from:scala/**")
  )

  val noDocs = Seq(
    publishArtifact in (Compile, packageDoc) in ThisBuild := false
  )

  private[this] def publishTarget(url: String) = {
    // Append build.timestamp to Artifactory URL to get consistent build numbers (see https://github.com/sbt/sbt/issues/2088):
    val url2 = if(url.startsWith("file:")) url else url.replaceAll("/$", "") + ";build.timestamp=" + System.currentTimeMillis

    Seq(
      publishTo in Global := Some("scala-pr-publish" at url2),
      credentials in Global += Credentials("Artifactory Realm", "scala-ci.typesafe.com", "scala-ci", env("PRIVATE_REPO_PASS"))
    )
  }

  // If fileOrUrl is already a file:, http: or https: URL, return it, otherwise treat it as a local file and return a URL for it
  private[this] def fileToUrl(fileOrUrl: String): String =
    if(fileOrUrl.startsWith("file:") || fileOrUrl.startsWith("http:") || fileOrUrl.startsWith("https:")) fileOrUrl
    else Paths.get(fileOrUrl).toUri.toString

  /** Like `Def.sequential` but accumulate all results */
  def sequence[B](tasks: List[Def.Initialize[Task[B]]]): Def.Initialize[Task[List[B]]] = tasks match {
    case Nil => Def.task { Nil }
    case x :: xs => Def.taskDyn {
      val v = x.value
      sequence(xs).apply((t: Task[List[B]]) => t.map(l => v :: l))
    }
  }
}
