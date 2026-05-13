plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("xyz.jpenilla.run-paper") version "2.2.2"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "xyz.qincai.celeryutils"
version = "1.0.0"
description = "Utility plugin for Minecraft Servers and SMPs"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jcenter.bintray.com/")
}

dependencies {
    paperweight.paperDevBundle("1.20.6-R0.1-SNAPSHOT")
    
    // JDA for Discord integration
    implementation("net.dv8tion:JDA:5.0.0") {
        exclude(module = "opus-java")
    }
    
    // Vault API for economy integration
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
    
    shadowJar {
        relocate("net.dv8tion.jda", "xyz.qincai.celeryutils.jda")
        archiveBaseName.set("CeleryUtils")
        archiveClassifier.set("")
    }
    
    runServer {
        minecraftVersion("1.20.6")
    }
}
