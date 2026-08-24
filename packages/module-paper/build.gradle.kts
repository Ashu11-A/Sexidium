import org.gradle.api.tasks.SourceSetContainer

plugins {
  java
}

repositories {
  // FancyNpcs / FancyHolograms publish their plugin jars (which embed the public `de.oliver.*.api`
  // packages) on Modrinth's maven. The project's preferred repo (repo.fancyplugins.de) is used for the
  // dedicated `:api` classifier, but Modrinth carries the same compile-time API and resolves here.
  maven("https://api.modrinth.com/maven") {
    name = "modrinth"
    content {
      includeGroup("maven.modrinth")
    }
  }
  // GeyserMC / Floodgate / Cumulus — for the Bedrock-form menu renderer. Restricted to the geyser
  // groups so it never shadows other artifacts. compileOnly only; Floodgate provides these at runtime.
  maven("https://repo.opencollab.dev/main/") {
    name = "opencollab"
    content {
      includeGroupByRegex("org\\.geysermc.*")
    }
  }
  // Multiverse-Core (latest v5 line) — the world-management backend Sexidium imports its worlds into.
  // Restricted to the MV group. compileOnly: the MV plugin provides it at runtime (hard depend).
  maven("https://repo.onarandombox.com/content/groups/public/") {
    name = "onarandombox"
    content {
      includeGroup("org.mvplugins.multiverse.core")
    }
  }
}

dependencies {
  // Pinned to the OLDEST Minecraft the jar targets, not the newest available. Paper refuses to load a
  // plugin whose plugin.yml api-version exceeds the running server but accepts an older one with legacy
  // shims, so building against 26.1.2 yields one artifact that runs on 26.1.2 AND 26.2 — where building
  // against 26.2 would hard-fail on 26.1.2. 26.1.2 is the pin because it is the Minecraft version
  // BetterHud's newest shader overlay actually matches; see PAPER_VERSION in scripts/init-paper.sh and
  // F62 in docs/known-issues.md. Keep in step with plugin.yml's api-version.
  compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
  // BetterHud 2.0.0 — the API artifact AND the runtime jar scripts/init-paper.sh installs, verified
  // byte-identical for the members BetterHudApi calls. Three things about 2.0.0 are load-bearing:
  //   * BetterHud-standard-api / -fabric-api are DEAD at 1.14.1; BetterHud-api (pulled in transitively
  //     here) and BetterHud-mod-api are the 2.x successors. Do not re-add the old coordinates.
  //   * The jars are class-file major 69, so the Java 25 toolchain below is a hard requirement.
  //   * HudPlayer now extends kr.toxicity.command.BetterCommandSource, so io.github.toxicity188:
  //     BetterCommand must resolve on the compile classpath (it does, transitively) or javac fails on
  //     the missing supertype even though nothing references it.
  // BetterHudLink gates every typed call behind a Class.forName + LinkageError probe, so a future
  // incompatible release degrades to the sidebar renderer rather than to a crash.
  compileOnly("io.github.toxicity188:BetterHud-bukkit-api:2.0.0")
  compileOnly("org.xerial:sqlite-jdbc:3.53.1.0")
  // Multiverse-Core v5.7.3 (latest, supports MC 1.18.2–26.2). compileOnly: the running server
  // provides it. Sexidium drives MV through a reflective bridge for version resilience, so this pin is
  // for API reference + version alignment; the runtime hard depend lives in plugin.yml.
  compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.7.3")
  // Lobby NPC backend. compileOnly: these plugins are softdepends provided at runtime.
  compileOnly("maven.modrinth:fancynpcs:2.11.0")
  compileOnly("maven.modrinth:fancyholograms:2.11.0")
  // Bedrock UI. compileOnly softdepend: present -> native Cumulus forms for Geyser players,
  // absent -> chest fallback. Never shaded (Floodgate ships Cumulus at runtime).
  compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
  // Cumulus 1.1.2 is the version Floodgate 2.2.x's API signatures were compiled against (it ships both
  // the legacy and modern form packages). Cumulus 2.0.0 removed the legacy classes, which breaks
  // overload resolution of FloodgateApi#sendForm at compile time.
  compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
  implementation(project(":packages:core"))
  testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testImplementation("org.mockito:mockito-core:5.14.2")
  testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

sourceSets.main {
  resources.srcDir(rootProject.layout.buildDirectory.dir("generated/botres"))
}

tasks.processResources {
  dependsOn(rootProject.tasks.named("stageBotResources"))
  val props = mapOf("version" to project.version.toString())
  inputs.properties(props)
  filesMatching("plugin.yml") {
    expand(props)
  }
}

tasks.test {
  useJUnitPlatform()
  jvmArgs(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "-Dnet.bytebuddy.experimental=true"
  )
}

tasks.jar {
  archiveBaseName.set("Sexidium-Paper")
  archiveClassifier.set("")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  // Ensure core's resources (incl. any embedded lobby bundle) are built before they are copied in.
  dependsOn(project(":packages:core").tasks.named("classes"))
  from(project(":packages:core").extensions.getByType<SourceSetContainer>()["main"].output)
}
