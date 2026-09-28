// Container images for the two services (`sbt api/Docker/publishLocal solver/Docker/publishLocal`).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
// Formatting, with the same .scalafmt.conf ankka uses.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
// Releases: sbt-dynver versions from git tags, signing, and the Central Portal (`sbt ci-release`).
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
