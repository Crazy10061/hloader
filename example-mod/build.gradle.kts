plugins {
    java
    id("com.hloader.plugin")
}

description = "A minimal mod, for testing the loader end to end."

hloader {
    minecraftVersion.set("1.8.9")
}

dependencies {
    compileOnly(project(":"))
    compileOnly("org.spongepowered:mixin:0.8.7")
}

tasks.jar {
    archiveBaseName.set("example-mod")
}
