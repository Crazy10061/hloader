plugins {
    java
    id("com.hloader.plugin")
}

description = "A minimal mod, for testing the loader end to end."

hloader {
    //minecraftVersion.set("latest")
    //minecraftVersion.set("1.12.2")
    //minecraftVersion.set("1.8.9")
    minecraftVersion.set("1.0")
    //minecraftVersion.set("rd-132211")
}

dependencies {
    compileOnly(project(":"))
    compileOnly("org.spongepowered:mixin:0.8.7")
}

tasks.jar {
    archiveBaseName.set("example-mod")
}
