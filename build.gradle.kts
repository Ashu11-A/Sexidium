
plugins {
    base
}

allprojects {
    group = "com.sexidium"
    version = "1.0.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        // fabricmc/minecraftforge/neoforged repos removidos: nenhuma dependência do repo usa esses
        // grupos (confirmado por grep em todos os build.gradle.kts) -- eram 3 handshakes HTTPS por
        // resolução de dependências que nunca acertavam nada aqui.
    }
}

subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(project.path
        .removePrefix(":")
        .replace(':', '-')))

    // Divide os testes de CADA módulo entre várias JVMs. É onde está o tempo: :packages:core
    // sozinho tem ~1.800 testes e rodava todos num fork só, num host de 24 núcleos.
    //
    // O teto de 4 não é timidez, é o mem_limit de 6g do container `init`: cada fork é uma JVM
    // inteira, e 24 delas com o daemon ao lado levariam o provisionamento a um OOM-kill --
    // que é SIGKILL, sem relatório de teste nenhum, ou seja um deploy que falha sem dizer por
    // quê. 4 forks x 1g + 2g do daemon = 6g no pior caso, e o pior caso não é o normal: os
    // forks só coexistem enquanto há classes suficientes na fila.
    //
    // O HEAP POR FORK É 1g, E NÃO PODE SER MUITO MENOR. O default do Gradle aqui não é um
    // número redondo qualquer: sem `maxHeapSize` o fork herda o default da JVM, que é 1/4 da
    // RAM FÍSICA -- vários GB nesta máquina. Fixar 512m parecia conservador e derrubou
    // WorldLeaseFenceConcurrencyTest (8 threads x 200 rodadas sobre um SQLite) com o fork
    // morrendo inteiro: no Gradle isso aparece como `java.io.EOFException` na tarefa, que não
    // se parece nem um pouco com falta de memória. 1g passa com folga.
    //
    // SEGURO PORQUE O GRADLE PARALELIZA POR CLASSE. Três testes deste repo escrevem em caminhos
    // FIXOS (`/tmp/sexidium-shared-test`, `/tmp/fake-plugin-data`, `/tmp/<runtimeName>`); como
    // duas instâncias da mesma classe nunca caem em forks diferentes, e os três caminhos são
    // distintos entre si, não há corrida. Um teste NOVO com caminho fixo tem de continuar
    // respeitando isso -- prefira @TempDir, que 59 classes daqui já usam.
    // Ajustável sem editar este arquivo: `-PtestForks=1` volta ao comportamento sequencial, que é
    // o que se quer ao investigar um teste que só falha acompanhado.
    tasks.withType<Test>().configureEach {
        val requested = (project.findProperty("testForks") as String?)?.toIntOrNull()
        maxParallelForks = requested
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        maxHeapSize = (project.findProperty("testHeap") as String?) ?: "1g"
    }
}

val botDir = layout.projectDirectory.dir("bot")

// ---- Pure build-script helpers ------------------------------------------------------------------
// zipDirectoryInto/unzipInto/unzipFileInto/worldContentRoot/sha256Of vivem em
// buildSrc/src/main/kotlin/SexidiumBuildUtil.kt, NAO aqui.
//
// Tentou-se duas vezes mante-los neste arquivo: como `fun`s de topo e depois como um `object`
// aninhado. Ambas quebram o configuration cache -- tudo declarado num .gradle.kts vira membro da
// classe do script, e o Gradle recusa serializar referencia a classe de build script. Com
// org.gradle.configuration-cache=true (como docker/provision.sh invoca) o build FALHAVA inteiro:
// `cannot serialize Gradle script object references` em generateBuildInfo, prepareLobbyBundle,
// prepareMapBundle e prepareMenuPack. Em buildSrc sao classe normal e o cache aceita.

// ---- Optional lobby bundle ---------------------------------------------------------------------
// Embeds a world + fake-player settings into the plugin jars at build time. Opt-in via properties:
//   -PlobbyWorldZip=assets/worlds/lobbies/Medieval-BreadBuilds.zip   (a ready-made world .zip)   OR
//   -PlobbyWorldDir=test/paper/worlds/lobby   (a world folder to zip)
//   -PlobbySettingsDir=test/paper/plugins/Sexidium   (a settings folder, e.g. fakeplayers/*.yml)
// Produces generated/lobby-bundle/bundled/{world,settings}.zip, which core's resources pick up.
val lobbyBundleOutputDir = layout.buildDirectory.dir("generated/lobby-bundle/bundled")
val prepareLobbyBundle by tasks.registering {
    // Vinculo local, lido pelo doLast abaixo. Um `val` de topo de .gradle.kts e campo da CLASSE
    // DO SCRIPT: le-lo de dentro de uma task action faz o lambda guardar um `this$0` para o script,
    // e o configuration cache recusa serializar isso ("cannot serialize Gradle script object
    // references"). Copiado para um local do bloco de configuracao, o lambda captura o valor.
    val outDirProvider = lobbyBundleOutputDir
    val worldZip = providers.gradleProperty("lobbyWorldZip").orNull
    val worldDir = providers.gradleProperty("lobbyWorldDir").orNull
    val settingsDir = providers.gradleProperty("lobbySettingsDir").orNull
    // Resolved to File HERE, at configuration time, where Project.file() is legal -- doLast below must
    // not call file()/project itself (configuration cache; see the SexidiumBuildUtil comment above).
    val worldZipFile = worldZip?.let { file(it) }
    val worldDirFile = worldDir?.let { file(it) }
    val settingsDirFile = settingsDir?.let { file(it) }
    inputs.property("lobbyWorldZip", worldZip ?: "")
    inputs.property("lobbyWorldDir", worldDir ?: "")
    inputs.property("lobbySettingsDir", settingsDir ?: "")
    // The archive/folder contents are inputs too, so swapping in a different world at the same path rebuilds.
    if (worldZip != null) inputs.files(worldZip)
    outputs.dir(lobbyBundleOutputDir)
    outputs.cacheIf { true }
    doLast {
        val outDir = outDirProvider.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        when {
            worldZipFile != null -> {
                if (worldZipFile.isFile) {
                    // Re-pack rather than copy: a downloaded map usually nests the world under a wrapper
                    // folder ("My Map/level.dat"), which would land as lobby/My Map/... on extraction.
                    val temp = temporaryDir.resolve("world-zip")
                    temp.deleteRecursively()
                    SexidiumBuildUtil.unzipFileInto(worldZipFile, temp)
                    val (contentRoot, flattened) =
                        SexidiumBuildUtil.worldContentRoot(temp, temporaryDir.resolve("world-stage"))
                    if (flattened) {
                        logger.lifecycle("Lobby bundle: '${worldZipFile.name}' uses dimension storage; " +
                            "flattened overworld to top level")
                    }
                    SexidiumBuildUtil.zipDirectoryInto(contentRoot, outDir.resolve("world.zip"))
                    logger.lifecycle("Lobby bundle: embedded world zip from $worldZipFile")
                } else {
                    logger.warn("Lobby bundle: lobbyWorldZip '$worldZipFile' not found; no world embedded")
                }
            }
            worldDirFile != null -> {
                if (worldDirFile.isDirectory) {
                    SexidiumBuildUtil.zipDirectoryInto(worldDirFile, outDir.resolve("world.zip"))
                    logger.lifecycle("Lobby bundle: embedded world folder from $worldDirFile")
                } else {
                    logger.warn("Lobby bundle: lobbyWorldDir '$worldDirFile' not found; no world embedded")
                }
            }
        }
        if (settingsDirFile != null) {
            if (settingsDirFile.isDirectory) {
                SexidiumBuildUtil.zipDirectoryInto(settingsDirFile, outDir.resolve("settings.zip"))
                logger.lifecycle("Lobby bundle: embedded settings from $settingsDirFile")
            } else {
                logger.warn("Lobby bundle: lobbySettingsDir '$settingsDirFile' not found; no settings embedded")
            }
        }
    }
}

// ---- Optional menu art textures -----------------------------------------------------------------
// Stages real GUI textures into the jar so the menu resource pack ships custom "Nexo-style" art
// instead of generated placeholders. Opt-in via properties:
//   -PmenuPackZip=build/sexidium-menu-pack.zip   (a zip whose entries are at the pack texture paths
//                                                 gui/menu/<id>.png, item/<section>/<name>.png)  OR
//   -PmenuPackDir=some/dir                        (a folder laid out the same way)
// The textures land at generated/menu-pack/bundled/menupack-textures/<path>, which core's resources
// pick up; SexidiumResourcePack uses each one in place of its placeholder. A manifest.txt listing
// every staged texture path is written alongside them so SexidiumResourcePack can also ship the
// non-referenced icons (the full assets/icons set) as bare textures. scripts/init-paper.sh assembles the
// zip from ./assets and passes -PmenuPackZip.
val menuPackGeneratedDir = layout.buildDirectory.dir("generated/menu-pack")
val prepareMenuPack by tasks.registering {
    // Vinculo local, lido pelo doLast abaixo. Um `val` de topo de .gradle.kts e campo da CLASSE
    // DO SCRIPT: le-lo de dentro de uma task action faz o lambda guardar um `this$0` para o script,
    // e o configuration cache recusa serializar isso ("cannot serialize Gradle script object
    // references"). Copiado para um local do bloco de configuracao, o lambda captura o valor.
    val menuPackDirProvider = menuPackGeneratedDir
    val packZip = providers.gradleProperty("menuPackZip").orNull
    val packDir = providers.gradleProperty("menuPackDir").orNull
    // Resolved to File HERE, at configuration time, where Project.file() is legal -- doLast below must
    // not call file()/project itself (configuration cache; see the SexidiumBuildUtil comment above).
    val packZipFile = packZip?.let { file(it) }
    val packDirFile = packDir?.let { file(it) }
    inputs.property("menuPackZip", packZip ?: "")
    inputs.property("menuPackDir", packDir ?: "")
    // The zip/dir contents are an input too, so re-running with the same path but new textures rebuilds.
    if (packZip != null) inputs.files(packZip)
    if (packDirFile != null && packDirFile.isDirectory) inputs.dir(packDirFile)
    outputs.dir(menuPackGeneratedDir)
    outputs.cacheIf { true }
    doLast {
        val bundled = menuPackDirProvider.get().asFile.resolve("bundled")
        bundled.deleteRecursively()
        val texturesDir = bundled.resolve("menupack-textures")
        texturesDir.mkdirs()
        when {
            packZipFile != null -> {
                if (packZipFile.isFile) {
                    SexidiumBuildUtil.unzipInto(packZipFile, texturesDir)
                    logger.lifecycle("Menu pack: staged custom textures from zip $packZipFile")
                } else {
                    logger.warn("Menu pack: menuPackZip '$packZipFile' not found; using placeholder art")
                }
            }
            packDirFile != null -> {
                if (packDirFile.isDirectory) {
                    // Manual recursive copy, not Project.copy{}: doLast cannot capture `project` under
                    // the configuration cache. copyRecursively mirrors packDirFile's CONTENTS into
                    // texturesDir -- the same semantics as CopySpec's from(dir).into(target).
                    packDirFile.copyRecursively(texturesDir, overwrite = true)
                    logger.lifecycle("Menu pack: staged custom textures from dir $packDirFile")
                } else {
                    logger.warn("Menu pack: menuPackDir '$packDirFile' not found; using placeholder art")
                }
            }
            else -> logger.lifecycle("Menu pack: no -PmenuPackZip/-PmenuPackDir; using placeholder art")
        }
        // Manifest of every staged texture path (relative to menupack-textures/, forward slashes),
        // sorted for determinism. SexidiumResourcePack ships referenced icons with a model and the
        // remaining manifest entries as bare textures. Empty/absent in placeholder builds.
        val manifest = texturesDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .map { it.relativeTo(texturesDir).invariantSeparatorsPath }
            .sorted()
            .toList()
        texturesDir.resolve("manifest.txt").writeText(manifest.joinToString("\n"))
        if (manifest.isNotEmpty()) {
            logger.lifecycle("Menu pack: staged ${manifest.size} textures (manifest.txt written)")
        }
    }
}

// ---- Bundled minigame maps ----------------------------------------------------------------------
// Stages the hand-picked TNT War map worlds (assets/worlds/tntwars/*.zip) into the jar so a fresh
// server auto-extracts them into its world root on first boot (see com.sexidium.core.world.MapBundle),
// where each is a clone template named by minigames.tntwar.maps. The source zips wrap their world in a
// single top folder (some with spaces), so each is normalised: locate the folder that holds level.dat
// and re-zip ITS contents, so entries start at level.dat — the same root-level layout LobbyBundle/
// MapBundle extract. A manifest.txt lists every staged map as "<world-path> <sha256-of-source-zip>"
// (tntwar/<id>) for the runtime, whose digest is what makes a re-exported map replace an already-seeded one.
val mapBundleGeneratedDir = layout.buildDirectory.dir("generated/map-bundle")
val tntwarMapsDir = layout.projectDirectory.dir("assets/worlds/tntwars")
val prepareMapBundle by tasks.registering {
    // Vinculo local, lido pelo doLast abaixo. Um `val` de topo de .gradle.kts e campo da CLASSE
    // DO SCRIPT: le-lo de dentro de uma task action faz o lambda guardar um `this$0` para o script,
    // e o configuration cache recusa serializar isso ("cannot serialize Gradle script object
    // references"). Copiado para um local do bloco de configuracao, o lambda captura o valor.
    val mapBundleDirProvider = mapBundleGeneratedDir
    val tntwarDir = tntwarMapsDir
    inputs.dir(tntwarMapsDir)
    outputs.dir(mapBundleGeneratedDir)
    outputs.cacheIf { true }
    doLast {
        val bundledMaps = mapBundleDirProvider.get().asFile.resolve("bundled/maps")
        bundledMaps.deleteRecursively()
        val mapsOut = bundledMaps.resolve("tntwar")
        mapsOut.mkdirs()
        val sourceZips = tntwarDir.asFile.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        val worldPaths = mutableListOf<String>()
        for (zip in sourceZips) {
            val id = zip.nameWithoutExtension
            val temp = temporaryDir.resolve("unzip/$id")
            temp.deleteRecursively()
            SexidiumBuildUtil.unzipFileInto(zip, temp)
            val (contentRoot, flattened) =
                SexidiumBuildUtil.worldContentRoot(temp, temporaryDir.resolve("stage/$id"))
            if (flattened) {
                logger.lifecycle("Map bundle: '$id' uses dimension storage; flattened overworld to top level")
            }
            SexidiumBuildUtil.zipDirectoryInto(contentRoot, mapsOut.resolve("$id.zip"))
            // "<world-path> <sha256>" — the digest is what lets a re-exported map replace the copy already
            // extracted on a server. A line without one still parses (older jars wrote just the path).
            worldPaths.add("tntwar/$id ${SexidiumBuildUtil.sha256Of(zip)}")
            logger.lifecycle("Map bundle: staged '$id' from $zip")
        }
        bundledMaps.resolve("manifest.txt").writeText(worldPaths.sorted().joinToString("\n"))
        if (worldPaths.isNotEmpty()) {
            logger.lifecycle("Map bundle: ${worldPaths.size} maps staged (manifest.txt written)")
        }
    }
}

// ---- Build stamp -------------------------------------------------------------------------------
// There was no build identity anywhere the JVM could read: the version is a literal above, and the
// only real stamp in the system was a jar sha256 in server/.provisioned that nothing inside the
// process could see. So "did that node come back on the build I staged?" — the one question a
// rolling update has to answer before it moves to the next node — was unanswerable from inside.
//
// The buildId here stamps the jar. The AUTHORITATIVE value at runtime is -Dsexidium.build.id, which
// the provisioner writes per node: it is chosen by whatever pinned that node's jar, so it cannot
// race a rebuild. Where the two disagree the runtime property wins (see BuildIdentity).
//
// Precedence is -PbuildId, then `git rev-parse`, then the literal "nogit" -- unchanged from before.
// What changed is HOW the git call is made: a raw ProcessBuilder.start() at configuration time is
// exactly the kind of thing the configuration cache flags as an "external process started" problem,
// because its result gets frozen into the cache entry through an untracked side channel. providers.exec
// is the Provider-API equivalent Gradle offers FOR configuration-time process execution and is safe to
// use in a configuration-cache build (it still executes eagerly here, inside runCatching, exactly like
// the old code did -- only the mechanism changed, not the timing or the fallback behaviour).
//
// In production this is moot either way: -PbuildId is never passed (grep confirms), and the source sync
// excludes .git, so `git rev-parse` always fails there and sexidiumBuildId is always "nogit", same as
// before this fix. The fix matters for local/dev builds (where .git exists) and for whenever the deploy
// scripts start passing -PbuildId=<commit sha> from the host, which they don't today -- see the report.
val sexidiumBuildId: String = providers.gradleProperty("buildId").orNull
    ?: runCatching {
        val exec = providers.exec {
            commandLine("git", "rev-parse", "--short=10", "HEAD")
            workingDir = rootDir
            isIgnoreExitValue = true
        }
        val output = exec.standardOutput.asText.get().trim()
        val exitValue = exec.result.get().exitValue
        if (exitValue == 0 && output.matches(Regex("[0-9a-f]{7,40}"))) output else null
    }.getOrNull()
    ?: "nogit"

val buildInfoGeneratedDir = layout.buildDirectory.dir("generated/buildinfo")
val generateBuildInfo by tasks.registering {
    // Vinculo local, lido pelo doLast abaixo. Um `val` de topo de .gradle.kts e campo da CLASSE
    // DO SCRIPT: le-lo de dentro de uma task action faz o lambda guardar um `this$0` para o script,
    // e o configuration cache recusa serializar isso ("cannot serialize Gradle script object
    // references"). Copiado para um local do bloco de configuracao, o lambda captura o valor.
    val buildInfoDirProvider = buildInfoGeneratedDir
    val buildIdLocal = sexidiumBuildId
    // Local val, not the bare `version` property: doLast below must not capture `project` (configuration
    // cache), and unqualified `version` inside doLast would resolve to Project.version exactly like
    // `file()`/`copy{}` did elsewhere in this file -- see the SexidiumBuildUtil comment above for why.
    val projectVersion = version.toString()
    // Declared as inputs so a version or id change re-runs the task and nothing else does. builtAt is
    // deliberately NOT an input: stamping the wall clock would make every build non-reproducible and
    // every incremental compile a full one.
    //
    // NOTE ON CACHE COST: today sexidiumBuildId is a constant per Gradle invocation ("nogit" in
    // production, always -- see the comment above), so this task is UP-TO-DATE except on the very first
    // run. compileJava is NOT affected by buildId changing anyway: build-info.properties is fed into
    // core's `resources` source set (see packages/core/build.gradle.kts), which only processResources
    // reads -- javac never sees it. So even if buildId DID change every build (e.g. once the deploy
    // scripts start passing -PbuildId=<commit sha> per the note above), the cost would be limited to
    // generateBuildInfo + processResources + jar/shadowJar re-running, a few seconds at most, never a
    // full javac recompile. No structural change was needed to keep buildId off the expensive path --
    // it already is, by virtue of Gradle's own java/resources source-set split.
    inputs.property("version", projectVersion)
    inputs.property("buildId", sexidiumBuildId)
    // The protocol tag is NOT written here on purpose. Protocol.java is its single source of truth
    // and ProtocolContractTest is what enforces bumping it; a copy in a generated properties file is
    // a second place to forget, and the two disagreeing is worse than only having one.
    outputs.dir(buildInfoGeneratedDir)
    outputs.cacheIf { true }
    doLast {
        val destination = buildInfoDirProvider.get().asFile.resolve("build-info.properties")
        destination.parentFile.mkdirs()
        destination.writeText(
            """
            # Generated by :generateBuildInfo. Read by com.sexidium.core.network.BuildIdentity.
            version=$projectVersion
            buildId=$buildIdLocal
            builtAt=${System.currentTimeMillis() / 1000L}
            """.trimIndent() + "\n")
        logger.lifecycle("Build stamp: $projectVersion+$buildIdLocal")
    }
}

// The Bun runtime is no longer bundled into the jar; BotManager downloads the correct build for the
// host's architecture at runtime (see com.sexidium.core.bot.BotManager). This keeps the plugin jar
// small and architecture-independent.
//
// Sync tasks already declare their own inputs/outputs (the from/into copy spec IS the tracked state),
// so this has real UP-TO-DATE support without anything extra. Not marked cacheIf: it is a plain local
// file copy of sources that are already on disk the moment this runs (no computation to save), so a
// build-cache round-trip would cost more than it saves.
val stageBotResources by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/botres"))
    from(botDir) {
        into("bot")
        include("src/**", "package.json", "bun.lock", "tsconfig.json", "constants.json")
        exclude("node_modules/**")
    }
    from(layout.projectDirectory.dir("packages/core/src/main/resources/db")) {
        into("packages/core/src/main/resources/db")
    }
}

// Same reasoning as stageBotResources above: Sync already tracks its own inputs/outputs, and this is a
// cheap local copy of jars the subproject tasks just built -- not worth build-caching.
val collectJars by tasks.registering(Sync::class) {
    val coreProject = project(":packages:core")
    val paperAdapterProject = project(":packages:module-paper")
    val velocityAdapterProject = project(":packages:module-velocity")
    val jarProjects = listOf(
        coreProject,
        paperAdapterProject,
        velocityAdapterProject)

    // The velocity module builds a SHADED jar (its plain jar is disabled, since it would ship
    // without the JDBC drivers Velocity has no way to load).
    dependsOn(coreProject.tasks.named("jar"))
    dependsOn(paperAdapterProject.tasks.named("jar"))
    // E dos ALIASES: o Sync abaixo copia o DIRETÓRIO libs/ do módulo Paper, e é o
    // paperVersionAliases que escreve os nomes por versão nele. Sem esta linha o Gradle
    // recusa o build ("uses this output ... without declaring an implicit dependency") --
    // e razão não lhe falta: sem ela, um assemble poderia sincronizar metade dos jars.
    dependsOn(paperAdapterProject.tasks.named("paperVersionAliases"))
    dependsOn(velocityAdapterProject.tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("libs"))

    from(paperAdapterProject.layout.buildDirectory.dir("libs")) {
      into("paper")
      include("*.jar")
    }

    // The proxy plugin. Kept in its own folder because dropping it into a Paper
    // plugins/ directory (or the reverse) fails at load with an unhelpful error.
    from(velocityAdapterProject.layout.buildDirectory.dir("libs")) {
      into("velocity")
      include("*.jar")
    }

    // Internal package jars are build artifacts, but they are not Paper plugins.
    // Keeping them out of build/libs/*.jar prevents accidental deployment.
    //
    // The core JAR TASK, not core's libs DIRECTORY. The directory form copied whatever happened to be
    // in there, which stopped being exactly one file when core gained a testFixtures source set: the
    // test-fixtures jar (test-only support code, shipped nowhere) would have been synced into
    // internal/ alongside the real one, and Gradle rejected the build outright because the directory
    // is another task's output that nothing here depended on. Naming the task copies exactly the one
    // artifact and carries its own task dependency.
    from(coreProject.tasks.named("jar")) {
        into("internal")
    }
}

tasks.build {
    dependsOn(collectJars)
}
