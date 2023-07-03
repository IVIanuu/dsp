package com.ivianuu.dsp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SliderColors
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.ivianuu.essentials.Lerper
import com.ivianuu.essentials.time.seconds
import com.ivianuu.essentials.ui.material.DefaultSliderRange
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.NoStepsStepPolicy
import com.ivianuu.essentials.ui.material.StepPolicy
import com.ivianuu.essentials.ui.material.stepValue
import com.ivianuu.injekt.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun <T : Comparable<T>> Slider(
  value: T,
  modifier: Modifier = Modifier,
  onValueChange: ((T) -> Unit)? = null,
  onValueChangeFinished: ((T) -> Unit)? = null,
  stepPolicy: StepPolicy<T> = NoStepsStepPolicy,
  enabled: Boolean = true,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  colors: SliderColors = SliderDefaults.colors(
    thumbColor = MaterialTheme.colors.secondary,
    activeTrackColor = MaterialTheme.colors.secondary,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
    disabledActiveTickColor = Color.Transparent,
    disabledInactiveTickColor = Color.Transparent
  ),
  @Inject lerper: Lerper<T>,
  @Inject valueRange: @DefaultSliderRange ClosedRange<T>,
) {
  fun T.toFloat() = lerper.unlerp(valueRange.start, valueRange.endInclusive, this)
  fun Float.toValue() = lerper.lerp(valueRange.start, valueRange.endInclusive, this)

  var internalValue: Float? by remember { mutableStateOf(null) }
  var internalValueEraseJob: Job? by remember { mutableStateOf(null) }

  val scope = rememberCoroutineScope()
  androidx.compose.material.Slider(
    internalValue ?: value.toFloat(),
    { newInternalValue ->
      internalValue = newInternalValue
      internalValueEraseJob?.cancel()
      onValueChange?.invoke(newInternalValue.toValue())
    },
    modifier,
    enabled,
    0f..1f,
    remember(stepPolicy, valueRange) { stepPolicy(valueRange) },
    {
      if (internalValue == null) return@Slider

      onValueChangeFinished?.invoke(stepPolicy.stepValue(internalValue!!.toValue(), valueRange))

      internalValueEraseJob?.cancel()
      internalValueEraseJob = scope.launch {
        delay(1.seconds)
        internalValue = null
      }
    },
    interactionSource,
    colors
  )
}

@Composable fun <T : Comparable<T>> SliderListItem(
  value: T,
  modifier: Modifier = Modifier,
  onValueChange: ((T) -> Unit)? = null,
  onValueChangeFinished: ((T) -> Unit)? = null,
  stepPolicy: StepPolicy<T> = NoStepsStepPolicy,
  title: (@Composable () -> Unit)? = null,
  subtitle: (@Composable () -> Unit)? = null,
  leading: (@Composable () -> Unit)? = null,
  valueText: @Composable ((T) -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
  textPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
  sliderAdjustmentPadding: Dp = 8.dp,
  @Inject lerper: Lerper<T>,
  @Inject valueRange: @DefaultSliderRange ClosedRange<T>,
) {
  var internalValue: T? by remember { mutableStateOf(null) }
  var internalValueEraseJob: Job? by remember { mutableStateOf(null) }

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
    val scope = rememberCoroutineScope()
    Slider(
      modifier = modifier,
      value = internalValue ?: value,
      onValueChange = { newValue ->
        internalValue = newValue
        internalValueEraseJob?.cancel()
        onValueChange?.invoke(newValue)
      },
      onValueChangeFinished = { newValue ->
        onValueChangeFinished?.invoke(newValue)
        internalValueEraseJob?.cancel()
        internalValueEraseJob = scope.launch {
          delay(1.seconds)
          internalValue = null
        }
      },
      stepPolicy = stepPolicy,
      valueRange = valueRange
    )
  }

  @Composable fun ValueTextContent() {
    Box(
      modifier = Modifier.widthIn(min = 72.dp),
      contentAlignment = Alignment.CenterEnd
    ) {
      CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.body2) {
        valueText!!(stepPolicy.stepValue(internalValue ?: value, valueRange))
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
