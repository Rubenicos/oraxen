plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://papermc.io/repo/repository/maven-public/") // Paper
}

dependencies {
    compileOnly(project(":core"))
    paperweight.paperDevBundle("1.19.2-R0.1-SNAPSHOT")
    pluginRemapper("net.fabricmc:tiny-remapper:0.10.4:fat")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}