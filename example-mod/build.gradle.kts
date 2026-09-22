plugins {
    java
}

description = "A minimal mod, for testing the loader end to end."

dependencies {
    compileOnly(project(":"))
    compileOnly("org.spongepowered:mixin:0.8.7")
    // The mixin below targets net.minecraft.bundler.Main directly (unobfuscated, no refmap needed).
    compileOnly(files(rootProject.file("demo-target/server.jar")))
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
