plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "cn.lyricraft"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Xms1G", "-Xmx2G")
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly("io.netty:netty-transport:4.1.115.Final")
    compileOnly("io.netty:netty-buffer:4.1.115.Final")
    compileOnly("io.netty:netty-common:4.1.115.Final")
    compileOnly("io.netty:netty-codec:4.1.115.Final")
}

tasks.jar {
    archiveFileName.set("MapEdit-${project.version}.jar")
}
