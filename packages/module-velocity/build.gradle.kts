plugins {
  java
  // The ONLY place shadow enters this build. Velocity has no `libraries:` loader (Paper downloads
  // JDBC from plugin.yml at enable time), so the proxy must carry its drivers in the jar.
  //
  // 9.x is required, not preference: Java 25 emits class file version 69, and 8.x ships an ASM that
  // rejects it ("Unsupported class file major version 69") the moment relocation reads a class.
  id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
  // 3.5.1 matches the proxy jar docker/provision.sh pins. Both the 3.x and 4.x
  // lines carry MINECRAFT_26_1 in their ProtocolVersion enum (verified by inspecting
  // the jars -- the PaperMC fill API exposes no supported-game-version field for
  // Velocity), so either speaks to this repo's Paper 26.1.2. 3.x wins because it is a
  // real release rather than a snapshot, and because Velocity 4 changed the plugin API:
  // a 4.x proxy will not load a plugin compiled against 3.x, so the pin here and the
  // VELOCITY_VERSION pin in lib/velocity.sh must move together.
  compileOnly("com.velocitypowered:velocity-api:3.5.1")
  // Generates velocity-plugin.json from the @Plugin annotation. Without it the proxy
  // finds no plugin descriptor in the jar and silently ignores it.
  annotationProcessor("com.velocitypowered:velocity-api:3.5.1")

  implementation(project(":packages:core"))

  // Shaded, because there is nothing to load them at runtime otherwise.
  //
  // NO sqlite-jdbc here, and that is a deployment fact rather than an omission: a proxy only
  // exists in a NETWORKED deployment, and a network requires a shared database, so the proxy
  // never opens SQLite. (SQLite is the standalone path, and standalone has no proxy.)
  //
  // It also cannot be shaded even if we wanted it: sqlite-jdbc's native library binds its JNI
  // symbols to the ORIGINAL package, so relocating the Java classes leaves libsqlitejdbc looking
  // for org/sqlite/core/NativeDB and failing with NoClassDefFoundError at the first connection.
  implementation("com.mysql:mysql-connector-j:9.1.0")
  implementation("org.postgresql:postgresql:42.7.4")

  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  // AbstractCoreApiSurfaceTest, shared with :packages:module-paper.
  testImplementation(testFixtures(project(":packages:core")))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testCompileOnly("com.velocitypowered:velocity-api:3.5.1")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
  useJUnitPlatform()
  configureSourceScanningTests(project)
}

// RELOCATION IS MANDATORY, not hygiene. Velocity's PluginClassLoader falls back to sibling
// plugin loaders, so an unrelocated `org.postgresql` in this jar could be handed to another
// plugin -- or ours could be satisfied by theirs, at a different version.
//
// Relocation rewrites the drivers' packages, which is why Database no longer does
// Class.forName(driverClass): it asks DriverManager to resolve a probe URL instead, and
// mergeServiceFiles() below preserves the META-INF/services/java.sql.Driver entries that make
// that discovery work after relocation.
tasks.shadowJar {
  archiveBaseName.set("Sexidium-Velocity")
  archiveClassifier.set("")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  // No explicit `from(core.output)`: `implementation(project(":packages:core"))` already merges
  // core's classes AND resources into the shaded jar. Adding the raw output again collides.

  relocate("com.mysql", "com.sexidium.libs.mysql")
  relocate("org.postgresql", "com.sexidium.libs.postgresql")

  // Without this the relocated drivers lose their service registration and DriverManager
  // finds nothing -- the exact failure the Class.forName removal was meant to avoid.
  mergeServiceFiles()
}

// `build` produces the shaded jar; the plain one would be missing every driver.
tasks.named("build") { dependsOn(tasks.shadowJar) }
tasks.jar { enabled = false }
