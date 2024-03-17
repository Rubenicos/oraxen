plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "1.5.11"
    id("maven-publish")
    alias(libs.plugins.shadowjar)
    id("org.ajoberstar.grgit.service") version "5.2.0"
}

val pluginVersion = project.property("pluginVersion") as String
tasks {
    //publish.get().dependsOn(shadowJar)
    shadowJar.get().archiveFileName.set("oraxen-${pluginVersion}.jar")
    build.get().dependsOn(shadowJar)
}

dependencies {
    paperweight.paperDevBundle("1.20.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}