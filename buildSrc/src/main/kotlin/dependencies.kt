/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ClassName", "unused")

object Build {
  const val applicationId = "com.ivianuu.dsp"
  const val compileSdk = 33
  const val minSdk = 28
  const val targetSdk = 31
  const val versionCode = 1
  const val versionName = "0.0.1"
}

object Deps {
  object Essentials {
    private const val version = "0.0.1-dev1218"
    const val android = "com.ivianuu.essentials:android:$version"
    const val backup = "com.ivianuu.essentials:backup:$version"
    const val boot = "com.ivianuu.essentials:boot:$version"
    const val foreground = "com.ivianuu.essentials:foreground:$version"
    const val gradlePlugin = "com.ivianuu.essentials:gradle-plugin:$version"
    const val permission = "com.ivianuu.essentials:permission:$version"
    const val rubik = "com.ivianuu.essentials:rubik:$version"
  }
}
