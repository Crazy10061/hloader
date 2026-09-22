plugins {
    application
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.hloader"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.hloader.Main"
        // Lets this same jar be used directly as `-javaagent:hloader-all.jar` for dev-run tasks,
        // separate from the "patch" CLI command which embeds this jar into a *different* target jar.
        attributes["Premain-Class"] = "com.hloader.agent.HloaderAgent"
        attributes["Can-Retransform-Classes"] = "true"
    }
    relocate("org.objectweb.asm", "com.hloader.shaded.asm")
    relocate("com.google.common", "com.hloader.shaded.guava")
    relocate("com.google.gson", "com.hloader.shaded.gson")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
