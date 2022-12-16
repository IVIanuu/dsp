/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ClassName", "unused")

object Build {
  const val applicationId = "com.ivianuu.dsp"
  const val compileSdk = 32
  const val minSdk = 31
  const val targetSdk = 31
  const val versionCode = 1
  const val versionName = "0.0.1"
}

object Deps {
  object Essentials {
    private const val version = "0.0.1-dev1135"
    const val android = "com.ivianuu.essentials:essentials-android:$version"
    const val boot = "com.ivianuu.essentials:essentials-boot:$version"
    const val foreground = "com.ivianuu.essentials:essentials-foreground:$version"
    const val gradlePlugin = "com.ivianuu.essentials:essentials-gradle-plugin:$version"
    const val permission = "com.ivianuu.essentials:essentials-permission:$version"
    const val rubik = "com.ivianuu.essentials:essentials-rubik:$version"
  }
}
