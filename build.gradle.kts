/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

buildscript {
  repositories {
    mavenLocal()
    google()
    mavenCentral()
    jcenter()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://plugins.gradle.org/m2")
  }

  dependencies {
    classpath(Deps.Essentials.gradlePlugin)
  }
}

allprojects {
  repositories {
    mavenLocal()
    google()
    mavenCentral()
    jcenter()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://plugins.gradle.org/m2")
  }
}