/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.dsp

import androidx.compose.ui.graphics.Color
import com.ivianuu.essentials.ResourceProvider
import com.ivianuu.essentials.rubik.Rubik
import com.ivianuu.essentials.ui.AppTheme
import com.ivianuu.essentials.ui.material.EsTheme
import com.ivianuu.essentials.ui.material.EsTypography
import com.ivianuu.essentials.ui.material.LightAndDarkColors
import com.ivianuu.essentials.ui.material.editEach
import com.ivianuu.injekt.Provide

object DspTheme {
  val Primary = Color(0xFFFC5C65)
  val Secondary = Color(0xFFF7B731)
}

@Provide val dspTheme = AppTheme { content ->
  EsTheme(
    colors = LightAndDarkColors(
      primary = DspTheme.Primary,
      secondary = DspTheme.Secondary
    ),
    typography = EsTypography.editEach { copy(fontFamily = Rubik) },
    content = content
  )
}
