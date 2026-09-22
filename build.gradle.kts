import java.net.URI

plugins {
    application
    java
}

group = "com.hloader"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

val asmVersion = "9.10.1"

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-analysis:$asmVersion")
    implementation("org.ow2.asm:asm-util:$asmVersion")
    implementation("org.spongepowered:mixin:0.8.7")
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("com.google.code.gson:gson:2.11.0")
}

application {
    mainClass.set("com.hloader.Main")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.hloader.Main"
    }
}

// A single runnable jar with ASM bundled in, so `java -jar hloader.jar` just works.
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a single runnable jar including its dependencies."
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.hloader.Main"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    with(tasks.jar.get() as CopySpec)
}

tasks.build {
    dependsOn("fatJar")
}

val demoServerJar = layout.projectDirectory.file("demo-target/server.jar")
val demoServerPatchedJar = layout.projectDirectory.file("demo-target/server-patched.jar")
val demoWorkDir = layout.projectDirectory.dir("demo-target/work-dir")

tasks.register("downloadLatestServerJar") {
    group = "hloader"
    description = "Downloads the latest official Minecraft server.jar into demo-target/server.jar."
    doLast {
        val slurper = groovy.json.JsonSlurper()

        val manifestText = URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").toURL().readText()
        @Suppress("UNCHECKED_CAST")
        val manifest = slurper.parseText(manifestText) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val releaseId = (manifest["latest"] as Map<String, String>)["release"]!!
        @Suppress("UNCHECKED_CAST")
        val versions = manifest["versions"] as List<Map<String, Any>>
        val versionUrl = versions.first { it["id"] == releaseId }["url"] as String

        val versionText = URI(versionUrl).toURL().readText()
        @Suppress("UNCHECKED_CAST")
        val versionJson = slurper.parseText(versionText) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val downloads = versionJson["downloads"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val serverUrl = (downloads["server"] as Map<String, Any>)["url"] as String

        logger.lifecycle("hloader: downloading Minecraft $releaseId server.jar from $serverUrl")
        val outFile = demoServerJar.asFile
        outFile.parentFile.mkdirs()
        URI(serverUrl).toURL().openStream().use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("hloader: saved $releaseId server.jar to ${outFile.path}")
    }
}

val patchDemoServer by tasks.registering(JavaExec::class) {
    group = "hloader"
    description = "Patches demo-target/server.jar with the hloader agent hook."
    dependsOn(tasks.named("fatJar"))
    onlyIf {
        val input = demoServerJar.asFile
        if (!input.exists()) {
            logger.lifecycle("hloader: skipping patchDemoServer, no jar at ${input.path}")
        }
        input.exists()
    }
    classpath = files(tasks.named("fatJar"))
    mainClass.set("com.hloader.Main")
    args = listOf("patch", demoServerJar.asFile.path, demoServerPatchedJar.asFile.path)
}

val installTestMod by tasks.registering {
    group = "hloader"
    description = "Builds example-mod and drops it into demo-target/work-dir/mods/."
    dependsOn(":example-mod:installToMods")
}

val runDemoServer by tasks.registering(Exec::class) {
    group = "hloader"
    description = "Runs the patched demo-target/server.jar from demo-target/work-dir/. Pass -PexportMixins to dump transformed classes to .mixin.out/."
    dependsOn(patchDemoServer, installTestMod)
    doFirst {
        demoWorkDir.asFile.mkdirs()
    }
    workingDir = demoWorkDir.asFile
    val exportMixins = project.hasProperty("exportMixins")
    commandLine(buildList {
        add("java")
        if (exportMixins) add("-Dmixin.debug.export=true")
        add("-jar")
        add(demoServerPatchedJar.asFile.path)
    })
    isIgnoreExitValue = true
}

tasks.register("demo") {
    group = "hloader"
    description = "All-in-one: build hloader, install the test mod, patch demo-target/server.jar, and run it."
    dependsOn(runDemoServer)
}
