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
import androidx.compose.runtime.DisposableEffect
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
import com.ivianuu.essentials.ui.material.DefaultSliderRange
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.NoStepsStepPolicy
import com.ivianuu.essentials.ui.material.StepPolicy
import com.ivianuu.essentials.ui.material.stepValue
import com.ivianuu.injekt.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
  var touchEndValue by remember { mutableStateOf(value) }
  var isTouching by remember { mutableStateOf(false) }

  if (!isTouching && value != touchEndValue)
    internalValue = value

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
          touchEndValue = value
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

@Composable fun <T : Comparable<T>> Slider(
  value: T,
  onValueChange: ((T) -> Unit)? = null,
  onValueChangeFinished: ((T) -> Unit)? = null,
  stepPolicy: StepPolicy<T> = NoStepsStepPolicy,
  valueRestoreDuration: Duration = Duration.INFINITE,
  modifier: Modifier = Modifier,
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

  var internalValue by remember { mutableStateOf(value.toFloat()) }
  var isTouching by remember { mutableStateOf(false) }
  var touchEndValue by remember { mutableStateOf(value) }
  if (!isTouching && touchEndValue != value)
    internalValue = value.toFloat()

  var valueChangeJob: Job? by remember { mutableStateOf(null) }
  val scope = rememberCoroutineScope()
  androidx.compose.material.Slider(
    internalValue,
    { newInternalValue ->
      isTouching = true
      internalValue = newInternalValue
      val newValue = internalValue.toValue()
      onValueChange?.invoke(newValue)

      valueChangeJob?.cancel()
      if (valueRestoreDuration < Duration.INFINITE) {
        valueChangeJob = scope.launch {
          delay(valueRestoreDuration)
          internalValue = value.toFloat()
        }
      }
    },
    modifier,
    enabled,
    0f..1f,
    remember(stepPolicy, valueRange) { stepPolicy(valueRange) },
    onValueChangeFinished?.let {
      {
        isTouching = false
        touchEndValue = value
        onValueChangeFinished(stepPolicy.stepValue(internalValue.toValue(), valueRange))
      }
    },
    interactionSource,
    colors
  )
}
