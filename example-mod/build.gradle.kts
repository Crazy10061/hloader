plugins {
    java
}

description = "A minimal mod, for testing the loader end to end."

val gameJar = rootProject.layout.buildDirectory.file("extracted/game.jar")
val librariesDir = rootProject.layout.buildDirectory.dir("extracted/libraries")

dependencies {
    compileOnly(project(":"))
    compileOnly("org.spongepowered:mixin:0.8.7")
    // Mixins below target net.minecraft.bundler.Main (the wrapper, unobfuscated by nature) and
    // net.minecraft.server.Main (the actual game entrypoint, extracted from server.jar - also unobfuscated).
    compileOnly(files(rootProject.file("demo-target/server.jar")))
    compileOnly(files(gameJar))
    // javac needs these on the classpath to fully resolve game.jar's own type annotations.
    compileOnly(fileTree(librariesDir) { include("*.jar") })
}

tasks.compileJava {
    dependsOn(rootProject.tasks.named("extractGameJar"))
    dependsOn(rootProject.tasks.named("extractLibraries"))
}

tasks.jar {
    archiveBaseName.set("example-mod")
}

tasks.register<Copy>("installToMods") {
    group = "hloader"
    description = "Builds this mod and yeets it into demo-target/work-dir/mods/."
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(rootProject.layout.projectDirectory.dir("demo-target/work-dir/mods"))
}
