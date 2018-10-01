resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

resolvers += "simplytyped" at "http://simplytyped.github.io/repo/releases"

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.1")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")

addSbtPlugin("com.simplytyped" % "sbt-antlr4" % "0.8.1")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.1")

addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.9.3")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
