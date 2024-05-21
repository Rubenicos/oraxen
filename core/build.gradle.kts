plugins {
    id("java")
    //id("io.papermc.paperweight.userdev") version "1.6.0"
    id("maven-publish")
    id("io.github.goooler.shadow") version "8.1.7"
    id("org.ajoberstar.grgit.service") version "5.2.0"
}

val pluginVersion = project.property("pluginVersion") as String
tasks {
    //publish.get().dependsOn(shadowJar)
    shadowJar.get().archiveFileName.set("oraxen-${pluginVersion}.jar")
    build.get().dependsOn(shadowJar)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    //paperweight.paperDevBundle("1.20.4-R0.1-SNAPSHOT")
}