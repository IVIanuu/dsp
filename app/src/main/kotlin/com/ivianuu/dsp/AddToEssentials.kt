package com.ivianuu.dsp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.ivianuu.essentials.Lerper
import com.ivianuu.essentials.ui.material.DefaultSliderRange
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.NoStepsStepPolicy
import com.ivianuu.essentials.ui.material.Slider
import com.ivianuu.essentials.ui.material.StepPolicy
import com.ivianuu.essentials.ui.material.stepValue
import com.ivianuu.injekt.Inject
import kotlin.time.Duration


@Composable fun <T : Comparable<T>> SliderListItem(
  value: T,
  onValueChange: ((T) -> Unit)? = null,
  onValueChangeFinished: ((T) -> Unit)? = null,
  stepPolicy: StepPolicy<T> = NoStepsStepPolicy,
  valueRestoreDuration: Duration = Duration.INFINITE,
  title: (@Composable () -> Unit)? = null,
  subtitle: (@Composable () -> Unit)? = null,
  leading: (@Composable () -> Unit)? = null,
  valueText: @Composable ((T) -> Unit)? = null,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
  textPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
  sliderAdjustmentPadding: Dp = 8.dp,
  @Inject lerper: Lerper<T>,
  @Inject valueRange: @DefaultSliderRange ClosedRange<T>,
) {
  var internalValue by remember { mutableStateOf(value) }
  var isTouching by remember { mutableStateOf(false) }

  if (isTouching)
    DisposableEffect(true) {
      onDispose {
        internalValue = value
      }
    }

  val textPadding = PaddingValues(
    start = max(
      textPadding.calculateStartPadding(LocalLayoutDirection.current) - sliderAdjustmentPadding,
      0.dp
    ),
    top = textPadding.calculateTopPadding(),
    bottom = textPadding.calculateBottomPadding(),
    end = textPadding.calculateEndPadding(LocalLayoutDirection.current)
  )

  @Composable fun SliderContent(modifier: Modifier = Modifier) {
    Slider(
      modifier = modifier,
      value = internalValue,
      onValueChange = { newValue ->
        isTouching = true
        internalValue = newValue
        if (newValue != value)
          onValueChange?.invoke(newValue)
      },
      onValueChangeFinished = onValueChangeFinished?.let {
        { newValue ->
          if (newValue != value)
            onValueChangeFinished(newValue)
          isTouching = false
        }
      },
      stepPolicy = stepPolicy,
      valueRange = valueRange,
      valueRestoreDuration = valueRestoreDuration
    )
  }

  @Composable fun ValueTextContent() {
    Box(
      modifier = Modifier.widthIn(min = 72.dp),
      contentAlignment = Alignment.CenterEnd
    ) {
      CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.body2) {
        valueText!!(stepPolicy.stepValue(internalValue, valueRange))
      }
    }
  }

  ListItem(
    modifier = modifier,
    title = title?.let {
      {
        Box(modifier = Modifier.padding(start = sliderAdjustmentPadding)) {
          title()
        }
      }
    },
    subtitle = {
      subtitle?.let {
        Box(modifier = Modifier.padding(start = sliderAdjustmentPadding)) {
          subtitle()
        }
      }

      SliderContent()
    },
    leading = leading,
    trailing = valueText?.let {
      { ValueTextContent() }
    },
    contentPadding = contentPadding,
    textPadding = textPadding
  )
}
