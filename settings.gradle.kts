pluginManagement {
    includeBuild("hloader-gradle-plugin")
}

rootProject.name = "hloader"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
    }
}

include("example-mod")
