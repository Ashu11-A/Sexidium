import org.gradle.api.artifacts.Configuration

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
  // VaultUnlockedAPI -- the economy/permissions broker API Sexidium REGISTERS ITS OWN Economy into.
  // Sexidium is the PROVIDER here, not a consumer: VaultUnlocked's own jar contains no economy at all,
  // only a ServicesManager lookup, so consuming it on a server that has no other economy plugin would
  // mean no economy exists. Restricted to the vault group so it never shadows another artifact.
  // compileOnly: the VaultUnlocked plugin (whose plugin.yml `name:` is literally `Vault`) provides
  // these classes at runtime, and a server without it simply never reaches the gated classes.
  maven("https://repo.codemc.io/repository/creatorfromhell/") {
    name = "codemc-creatorfromhell"
    content {
      includeGroup("net.milkbowl.vault")
    }
  }
}

dependencies {
  // Pinned to the OLDEST Minecraft the jar targets, not the newest available. Paper refuses to load a
  // plugin whose plugin.yml api-version exceeds the running server but accepts an older one with legacy
  // shims, so building against 26.1.2 yields one artifact that runs on 26.1.2 AND 26.2 — where building
  // against 26.2 would hard-fail on 26.1.2. 26.1.2 is the pin because it is the Minecraft version
  // BetterHud's newest shader overlay actually matches; see PAPER_VERSION in scripts/init-paper.sh and
  // F62 in docs/reference/known-issues.md. Keep in step with plugin.yml's api-version.
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
  // VaultUnlocked 2.20. The version has TWO numbers (major.minor) tracking the VaultUnlocked PLUGIN
  // versions this API is compatible with, so a bump here is a runtime-compat decision, not a patch
  // bump. The artifact ships BOTH net.milkbowl.vault2.economy.* AND the legacy
  // net.milkbowl.vault.economy.{Economy, AbstractEconomy} -- one coordinate covers both service
  // registrations, and both are needed because VaultUnlocked does NOT bridge vault2 back to vault1
  // while most shop plugins are still vault1-only. Its published Gradle metadata declares no
  // dependencies, so nothing transitive comes with it.
  compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20")
  implementation(project(":packages:core"))
  testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
  // AbstractCoreApiSurfaceTest, shared with :packages:module-velocity. Through the test-fixtures
  // variant rather than by reaching into core's test source set, for the same reason bundledCore
  // exists: a cross-project model read is what isolated projects forbids.
  testImplementation(testFixtures(project(":packages:core")))
  // On the TEST runtime, not just compileOnly: VaultRegistrationTest drives the real bridge, and the
  // bridge refuses to register when the API cannot be LINKED -- which is exactly what a compileOnly
  // dependency looks like at test time, so the happy path could never be reached.
  testImplementation("net.milkbowl.vault:VaultUnlockedAPI:2.20")
  // The same reason: VaultRegistrationTest builds a real EconomyService over a real SQLite file in a
  // @TempDir, because "did it register" is only worth asserting about a service that actually works.
  testImplementation("org.xerial:sqlite-jdbc:3.53.1.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testImplementation("org.mockito:mockito-core:5.14.2")
  testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Core is BUNDLED into this jar through a resolvable configuration, not by reaching into
// project(":packages:core")'s source-set output (the tasks.jar block below). That access was exactly
// the cross-project model read Gradle's isolated-projects mode forbids — the same failure that already
// forced buildSrc/ into existence here. A configuration resolves core's built artifact like any
// dependency instead: no model access, automatic task dependencies, and any later change is a
// one-line dependencies edit.
//
// It does NOT fix the source-set trap, and an earlier version of this comment wrongly claimed it did:
// what resolves here is core's jar, and core's jar packs sourceSets.main only. A new source set in
// :packages:core still lands in neither jar unless core's own tasks.jar is taught about it — with the
// failure mode being a Class.forName that misses, a gate that catches it, and a fallback that then
// runs forever on a server that did not need it. Add source sets to core's jar explicitly.
val bundledCore: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  // NOT transitive, and that is load-bearing rather than tidiness. tasks.jar below zipTree()s every
  // file this configuration resolves, so a transitive one would unpack core's runtime dependencies
  // into the plugin jar as an unrelocated fat jar the moment anyone adds an `implementation` line to
  // :packages:core — which the old source-set-output approach was structurally incapable of doing.
  // Core has no runtime dependencies today; this makes that a decision rather than an accident.
  isTransitive = false
}

dependencies {
  bundledCore(project(":packages:core"))
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
  configureSourceScanningTests(project)
}

// AS VERSÕES DO MINECRAFT QUE ESTE JAR ATENDE, e o contador `+N` de cada uma. Fonte:
// minecraft-targets.properties na raiz -- leia os comentários DE LÁ para o porquê de uma
// compilação só servir as duas; aqui fica apenas a mecânica.
//
// providers.fileContents e não File.readText(): é assim que o configuration cache enxerga o
// arquivo como INPUT. Lido com readText, uma edição no contador não invalidaria nada e o build
// devolveria o nome antigo de um cache reutilizado.
val minecraftTargets = parseMinecraftTargets(
  providers.fileContents(rootProject.layout.projectDirectory.file("minecraft-targets.properties"))
    .asText.get()
)
val floorTarget = minecraftTargets.first { it.isFloor }
val paperArtifactBase = "sexidium-paper"

tasks.jar {
  // O jar É o do piso: um artefato, compilado contra o paper-api do piso. Os nomes das outras
  // versões saem do paperVersionAliases abaixo, com os MESMOS bytes.
  archiveBaseName.set(paperArtifactBase)
  archiveVersion.set("${floorTarget.version}+${floorTarget.build}")
  archiveClassifier.set("")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  // provider{}, NOT bundledCore.files — eager resolution at configuration time is exactly what broke
  // generateBuildInfo/prepareLobbyBundle before the local-val discipline existed. The lambda captures
  // only the local, so the configuration cache can serialize it. dependsOn() is declared explicitly
  // because a mapped provider loses the implicit "core's jar must exist first" wiring a bare
  // FileCollection would carry.
  dependsOn(bundledCore.buildDependencies)
  val bundledCoreConfiguration = bundledCore
  from(provider { bundledCoreConfiguration.map { zipTree(it) } })
}

// Um nome por versão atendida ALÉM do piso, com os bytes do jar do piso.
//
// Copy e não uma segunda compilação: nada no código diverge entre 26.1.2 e 26.2 (o preenchimento
// do pacote de login é por NOME de componente, e o pack format sai de uma tabela em runtime), então
// recompilar contra o paper-api da 26.2 daria um jar de comportamento idêntico que só perderia a
// capacidade de carregar na 26.1.2. Ver minecraft-targets.properties.
//
// Uma TASK de cópia e não a tarefa Copy do Gradle, e a razão é uma armadilha que já mordeu: o
// destino dos aliases é O MESMO DIRETÓRIO onde mora o jar do piso. A Copy instala cada entrada
// em dest/<nome> -- que, para o próprio jar do piso, é ELE MESMO -- depois de o eachFile ter
// feito as cópias; o self-copy truncou o jar do PISO para 0 bytes, com os aliases ao lado cheios
// de bytes bons. Um jar vazio passa por [[ -s ]]? Não -- mas passaria por qualquer olhar menos
// atento até o primeiro boot. Aqui não há instalação implícita: só as cópias declaradas.
//
// Os bytes idênticos são VERIFICÁVEIS de propósito: `sha256sum build/libs/paper/*.jar` devolve o
// mesmo hash para todos, e é essa igualdade que prova que os nomes são o mesmo código.
val paperVersionAliases by tasks.registering {
    group = "sexidium"
    description = "Emite um jar por versão do Minecraft atendida (mesmos bytes do piso)."

    val jarFile = tasks.jar.flatMap { it.archiveFile }
    val aliases = minecraftTargets.filterNot { it.isFloor }
    val base = paperArtifactBase
    // Resolvidos AQUI para providers locais: dentro do doLast, um acesso a `layout`/`project`
    // vira "cannot serialize Gradle script object references" e o configuration cache recusa o
    // build inteiro -- o mesmo motivo pelo qual buildSrc existe neste projeto.
    val aliasFiles = aliases.map { layout.buildDirectory.file("libs/${it.jarFileName(base)}") }

    inputs.file(jarFile)
    outputs.files(aliasFiles)
    doLast {
        val source = jarFile.get().asFile
        aliasFiles.forEach { destination ->
            source.copyTo(destination.get().asFile, overwrite = true)
        }
    }
}

tasks.named("assemble") { dependsOn(paperVersionAliases) }
