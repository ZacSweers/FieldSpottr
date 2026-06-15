// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import dev.zacsweers.fieldspottr.PermitState.FieldState
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FieldGroup
import dev.zacsweers.fieldspottr.data.LiveFieldAvailability
import dev.zacsweers.fieldspottr.data.LiveGroupAvailability
import dev.zacsweers.fieldspottr.data.LivePermitAdvisory
import dev.zacsweers.fieldspottr.data.LivePermitBlock
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.data.withOverlapsFrom
import dev.zacsweers.fieldspottr.theme.fsColorScheme
import dev.zacsweers.fieldspottr.ui.WeatherGlyph
import dev.zacsweers.fieldspottr.util.AutoMeasureText
import dev.zacsweers.fieldspottr.util.ReflowText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val GridHorizontalPadding = 16.dp
private val TimeColumnWidth = 64.dp
private val FieldColumnMinWidth = 112.dp
private const val OverflowCueFadeDurationMillis = 280

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PermitGrid(
  selectedGroup: String,
  permits: PermitState?,
  areas: Areas,
  selectedDate: LocalDate,
  modifier: Modifier = Modifier,
  verticalScrollState: ScrollState = rememberScrollState(),
  horizontalScrollState: ScrollState = rememberScrollState(),
  autoScrollToFirstPermit: Boolean = true,
  onAutoScrolledToFirstPermit: () -> Unit = {},
  liveAvailability: LiveGroupAvailability? = null,
  weather: WeatherForecast? = null,
  cornerSlot: (@Composable () -> Unit)? = null,
  onEventClick: (fieldName: String, index: Int, Reserved, orgVisible: Boolean) -> Unit =
    { _, _, _, _ ->
    },
) {
  val group = areas.groups[selectedGroup] ?: return
  val resolvedLiveAvailability =
    remember(group, liveAvailability) { liveAvailabilityForGrid(group, liveAvailability) }
  val numColumns = group.fields.size
  if (numColumns == 0) return

  val itemHeight = 50.dp

  // Start at the earliest available permit or 8am
  val density = LocalDensity.current
  val initialEarliestPermit =
    remember(permits) {
      permits
        ?.fields
        ?.values
        ?.flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
        ?.minOrNull() ?: 8
    }

  val initialScrollPx =
    remember(initialEarliestPermit) {
      density.run { (initialEarliestPermit * itemHeight).roundToPx() }
    }

  BoxWithConstraints(modifier) {
    val availableFieldWidth =
      (maxWidth - GridHorizontalPadding * 2 - TimeColumnWidth).coerceAtLeast(0.dp)
    val fieldAreaWidth = maxOf(FieldColumnMinWidth * numColumns, availableFieldWidth)
    val fieldColumnWidth = fieldAreaWidth / numColumns.toFloat()
    val contentHeight = itemHeight * 24

    LaunchedEffect(
      permits,
      autoScrollToFirstPermit,
      initialScrollPx,
      verticalScrollState.maxValue,
    ) {
      if (permits == null) return@LaunchedEffect
      if (!autoScrollToFirstPermit) return@LaunchedEffect
      val earliestPermit =
        permits.fields.values
          .flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
          .minOrNull() ?: 8
      val target = density.run { (earliestPermit * itemHeight).roundToPx() }
      verticalScrollState.scrollTo(target.coerceAtMost(verticalScrollState.maxValue))
      onAutoScrolledToFirstPermit()
    }
    val isScrolled by remember { derivedStateOf { verticalScrollState.value > 0 } }
    val showPreviousFields by remember { derivedStateOf { horizontalScrollState.value > 0 } }
    val showMoreFields by remember {
      derivedStateOf { horizontalScrollState.value < horizontalScrollState.maxValue }
    }

    Column(Modifier.fillMaxSize()) {
      // Names of the fields as a header
      Surface {
        Box {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .padding(horizontal = GridHorizontalPadding)
                .padding(bottom = 8.dp),
            verticalAlignment = CenterVertically,
          ) {
            if (cornerSlot == null) {
              Spacer(Modifier.width(TimeColumnWidth))
            } else {
              Box(Modifier.width(TimeColumnWidth)) { cornerSlot() }
            }
            Box(
              Modifier.weight(1f)
                .clipToBounds()
                .horizontalOverflowCue(showPreviousFields, showMoreFields)
                .horizontalScroll(horizontalScrollState)
            ) {
              Row(Modifier.width(fieldAreaWidth)) {
                for (columnNumber in 0..<numColumns) {
                  val defaultTextStyle = MaterialTheme.typography.titleMedium
                  val textAlign = TextAlign.Center
                  AutoMeasureText(
                    modifier = Modifier.width(fieldColumnWidth),
                    minSize = 12.sp,
                    maxSize = defaultTextStyle.fontSize,
                    textAlign = textAlign,
                  ) { fontSize ->
                    Text(
                      group.fields[columnNumber].displayName,
                      textAlign = textAlign,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      softWrap = false,
                      style = defaultTextStyle,
                      fontSize = fontSize,
                    )
                  }
                }
              }
            }
          }
          androidx.compose.animation.AnimatedVisibility(
            visible = isScrolled,
            modifier = Modifier.align(BottomCenter),
          ) {
            HorizontalDivider()
          }
        }
      }

      Row(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .padding(start = GridHorizontalPadding, end = GridHorizontalPadding, bottom = 16.dp)
      ) {
        // Time column
        val hourlyWeather =
          remember(weather, selectedDate) {
            weather?.hourly(selectedDate)?.associateBy { it.hour }
          }
        Box(
          Modifier.width(TimeColumnWidth)
            .fillMaxHeight()
            .clipToBounds()
            .verticalScroll(verticalScrollState)
        ) {
          Column(Modifier.height(contentHeight)) {
            for (rowNumber in 0..<24) {
              Box(Modifier.height(itemHeight)) {
                // Time marker
                val adjustedTime = ((rowNumber) % 12).let { if (it == 0) 12 else it }
                val amPm = if (rowNumber < 12) "AM" else "PM"
                val hourForecast = hourlyWeather?.get(rowNumber)
                val isRainyHour = hourForecast?.isRainy == true
                Row(
                  modifier = Modifier.align(TopEnd).padding(4.dp),
                  verticalAlignment = CenterVertically,
                  horizontalArrangement = spacedBy(2.dp),
                ) {
                  if (hourForecast != null && hourForecast.condition.isPrecipitation) {
                    WeatherGlyph(
                      hourForecast.condition,
                      size = 11.dp,
                      tint =
                        if (isRainyHour) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Text(
                    "$adjustedTime $amPm",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color =
                      if (isRainyHour) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                  )
                }
              }
            }
          }
        }

        val fields = permits?.fields ?: PermitState.EMPTY.fields
        Box(
          Modifier.weight(1f)
            .fillMaxHeight()
            .clipToBounds()
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
        ) {
          Row(
            Modifier.width(fieldAreaWidth)
              .height(contentHeight)
              .nowIndicator(selectedDate, itemHeight)
          ) {
            for (field in group.fields) {
              val fieldStates = fields[field] ?: FieldState.EMPTY
              PermitGridColumn(
                fieldName = field.displayName,
                fieldStates = fieldStates,
                liveField = resolvedLiveAvailability?.fields?.get(field),
                itemHeight = itemHeight,
                modifier = Modifier.width(fieldColumnWidth),
                permits = permits,
                onEventClick = onEventClick,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Modifier.horizontalOverflowCue(
  showPrevious: Boolean,
  showMore: Boolean,
): Modifier {
  val previousAlpha by
    animateFloatAsState(
      targetValue = if (showPrevious) 1f else 0f,
      animationSpec =
        tween(durationMillis = OverflowCueFadeDurationMillis, easing = FastOutSlowInEasing),
      visibilityThreshold = 0.01f,
      label = "previousFieldsCue",
    )
  val moreAlpha by
    animateFloatAsState(
      targetValue = if (showMore) 1f else 0f,
      animationSpec =
        tween(durationMillis = OverflowCueFadeDurationMillis, easing = FastOutSlowInEasing),
      visibilityThreshold = 0.01f,
      label = "moreFieldsCue",
    )
  val surface = MaterialTheme.colorScheme.surface
  return drawWithContent {
    drawContent()
    val cueWidth = 112.dp.toPx().coerceAtMost(size.width)
    val transparentTint = surface.copy(alpha = 0f)
    if (previousAlpha > 0f) {
      drawRect(
        brush =
          Brush.horizontalGradient(
            colorStops =
              arrayOf(
                0f to surface.copy(alpha = 0.75f * previousAlpha),
                0.2f to surface.copy(alpha = 0.38f * previousAlpha),
                0.4f to surface.copy(alpha = 0.16f * previousAlpha),
                0.6f to surface.copy(alpha = 0.05f * previousAlpha),
                0.8f to surface.copy(alpha = 0.01f * previousAlpha),
                1f to transparentTint,
              ),
            startX = 0f,
            endX = cueWidth,
          ),
        size = Size(cueWidth, size.height),
      )
    }
    if (moreAlpha > 0f) {
      val cueStart = size.width - cueWidth
      drawRect(
        brush =
          Brush.horizontalGradient(
            colorStops =
              arrayOf(
                0f to transparentTint,
                0.2f to surface.copy(alpha = 0.01f * moreAlpha),
                0.4f to surface.copy(alpha = 0.05f * moreAlpha),
                0.6f to surface.copy(alpha = 0.16f * moreAlpha),
                0.8f to surface.copy(alpha = 0.38f * moreAlpha),
                1f to surface.copy(alpha = 0.75f * moreAlpha),
              ),
            startX = cueStart,
            endX = size.width,
          ),
        topLeft = Offset(cueStart, 0f),
        size = Size(cueWidth, size.height),
      )
    }
  }
}

internal fun liveAvailabilityForGrid(
  group: FieldGroup,
  liveAvailability: LiveGroupAvailability?,
): LiveGroupAvailability? {
  return liveAvailability?.withOverlapsFrom(group)
}

@Composable
private fun PermitGridColumn(
  fieldName: String,
  fieldStates: List<FieldState>,
  liveField: LiveFieldAvailability?,
  itemHeight: Dp,
  modifier: Modifier = Modifier,
  permits: PermitState?,
  onEventClick: (fieldName: String, index: Int, Reserved, orgVisible: Boolean) -> Unit,
) {
  val items = remember(fieldStates, liveField) { permitGridColumnItems(fieldStates, liveField) }
  Column(modifier.fillMaxWidth()) {
    var currentSlot = 0
    for (item in items) {
      if (item.startSlot < currentSlot) continue
      FreeGridSegments(currentSlot, item.startSlot, itemHeight)
      when (item) {
        is PermitGridColumnItem.Permit ->
          PermitGridEvent(
            fieldName = fieldName,
            event = item.reserved,
            index = item.index,
            itemHeight = itemHeight,
            permits = permits,
            onEventClick = onEventClick,
          )
        is PermitGridColumnItem.Advisory -> {
          key(fieldName, item.animationKey) {
            LivePermitAdvisoryOverlayEvent(item.advisory, itemHeight, item.durationSlots)
          }
        }
        is PermitGridColumnItem.Block -> {
          key(fieldName, item.animationKey) {
            LivePermitBlockOverlayEvent(item.block, itemHeight, item.durationSlots)
          }
        }
      }
      currentSlot = item.endSlot
    }
    FreeGridSegments(currentSlot, 48, itemHeight)
  }
}

internal fun permitGridColumnItems(
  fieldStates: List<FieldState>,
  liveField: LiveFieldAvailability?,
): List<PermitGridColumnItem> {
  val reservedEvents =
    fieldStates.filterIsInstance<Reserved>().mapIndexed { index, reserved ->
      PermitGridColumnItem.Permit(index, reserved)
    }
  val reservedSlots = reservedEvents.map { it.startSlot until it.endSlot }
  val liveItems =
    liveField
      ?.let { availability ->
        (availability.blocks.map(PermitGridColumnItem::Block) +
            availability.advisories.map(PermitGridColumnItem::Advisory))
          .flatMap { item -> item.subtract(reservedSlots) }
      }
      .orEmpty()
  return (reservedEvents + liveItems).sortedBy { it.startSlot }
}

@Composable
private fun FreeGridSegments(startSlot: Int, endSlot: Int, itemHeight: Dp) {
  var currentSlot = startSlot
  while (currentSlot < endSlot) {
    val nextHourSlot = if (currentSlot % 2 == 0) currentSlot + 2 else currentSlot + 1
    val nextSlot = minOf(endSlot, nextHourSlot)
    val modifier =
      Modifier.height(itemHeight * ((nextSlot - currentSlot) / 2f))
        .fillMaxWidth()
        .then(if (nextSlot % 2 == 0) Modifier.gridBottomDivider() else Modifier)
    Box(modifier)
    currentSlot = nextSlot
  }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PermitGridEvent(
  fieldName: String,
  event: Reserved,
  index: Int,
  itemHeight: Dp,
  permits: PermitState?,
  onEventClick: (fieldName: String, index: Int, Reserved, orgVisible: Boolean) -> Unit,
) {
  Box(Modifier.height(itemHeight * event.duration).fillMaxWidth().gridBottomDivider()) {
    key(permits) {
      SharedElementTransitionScope {
        val skipEntryAnimation = isTransitionActive
        val staggerDelay = index * 30L
        val animProgress = remember { Animatable(if (skipEntryAnimation) 1f else 0f) }
        LaunchedEffect(Unit) {
          if (skipEntryAnimation) return@LaunchedEffect
          delay(staggerDelay.milliseconds)
          animProgress.animateTo(1f, tween(300))
        }
        PermitEvent(
          fieldName = fieldName,
          index = index,
          event = event,
          modifier =
            Modifier.graphicsLayer {
              alpha = animProgress.value
              translationY = (1f - animProgress.value) * 12f
            },
          onEventClick = { event, orgVisible -> onEventClick(fieldName, index, event, orgVisible) },
        )
      }
    }
  }
}

@Composable
private fun LivePermitBlockOverlayEvent(
  block: LivePermitBlock,
  itemHeight: Dp,
  durationSlots: Int,
) {
  LivePermitOverlayContainer(itemHeight, durationSlots) {
    LivePermitBlock(block)
  }
}

@Composable
private fun LivePermitOverlayContainer(
  itemHeight: Dp,
  durationSlots: Int,
  content: @Composable () -> Unit,
) {
  val animProgress = remember { Animatable(0f) }
  val translationYPx = withDensity { 10.dp.toPx() }
  LaunchedEffect(Unit) {
    animProgress.animateTo(1f, tween(durationMillis = 250))
  }

  Box(Modifier.height(itemHeight * (durationSlots / 2f)).fillMaxWidth().gridBottomDivider()) {
    Box(
      Modifier.fillMaxSize().graphicsLayer {
        alpha = animProgress.value
        translationY = (1f - animProgress.value) * translationYPx
      }
    ) {
      content()
    }
  }
}

@Composable
private fun Modifier.gridBottomDivider(): Modifier {
  val color = MaterialTheme.colorScheme.outlineVariant
  return drawWithContent {
    drawContent()
    drawLine(
      color = color,
      start = Offset(0f, size.height - 0.5f),
      end = Offset(size.width, size.height - 0.5f),
      strokeWidth = 1f,
    )
  }
}

@Composable
private fun LivePermitBlock(block: LivePermitBlock) {
  if (block.isOverlap) {
    val shape = MaterialTheme.shapes.medium
    Surface(
      modifier =
        Modifier.fillMaxSize().padding(4.dp).background(MaterialTheme.colorScheme.surface, shape),
      color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
      shape = shape,
    ) {}
    return
  }

  val isPendingApproval = block.title == "Pending approval"
  val isIssuedPermit = block.status.startsWith("Issued permit")
  val fsColors = MaterialTheme.fsColorScheme
  val shape = MaterialTheme.shapes.medium
  val containerColor =
    when {
      isPendingApproval -> fsColors.pendingContainer
      isIssuedPermit -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.errorContainer
    }
  val textColor =
    when {
      isPendingApproval -> fsColors.onPendingContainer
      isIssuedPermit -> MaterialTheme.colorScheme.onSecondaryContainer
      else -> MaterialTheme.colorScheme.onErrorContainer
    }
  Surface(
    modifier =
      Modifier.fillMaxSize().padding(4.dp).background(MaterialTheme.colorScheme.surface, shape),
    color = containerColor,
    shape = shape,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
      ReflowText(
        text = block.title,
        sharedElementKey = null,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
        color = textColor,
      )
      ReflowText(
        text = block.org.ifEmpty { block.status },
        sharedElementKey = null,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        overflow = TextOverflow.Ellipsis,
        color = textColor.copy(alpha = 0.65f),
      )
    }
  }
}

@Composable
private fun LivePermitAdvisoryOverlayEvent(
  advisory: LivePermitAdvisory,
  itemHeight: Dp,
  durationSlots: Int,
) {
  LivePermitOverlayContainer(itemHeight, durationSlots) {
    LivePermitAdvisory(advisory)
  }
}

@Composable
private fun LivePermitAdvisory(advisory: LivePermitAdvisory) {
  val shape = MaterialTheme.shapes.medium
  Surface(
    modifier =
      Modifier.fillMaxSize().padding(4.dp).background(MaterialTheme.colorScheme.surface, shape),
    color = MaterialTheme.colorScheme.tertiaryContainer,
    shape = shape,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
      ReflowText(
        text = "Pending request",
        sharedElementKey = null,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      ReflowText(
        text = advisory.message,
        sharedElementKey = null,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.65f),
      )
    }
  }
}

internal sealed interface PermitGridColumnItem {
  val startSlot: Int
  val endSlot: Int
  val durationSlots: Int

  data class Permit(val index: Int, val reserved: Reserved) : PermitGridColumnItem {
    override val startSlot: Int = reserved.start * 2
    override val endSlot: Int = reserved.end * 2
    override val durationSlots: Int = endSlot - startSlot
  }

  data class Block(val block: LivePermitBlock) : PermitGridColumnItem {
    override val startSlot: Int = block.startSlot
    override val endSlot: Int = block.endSlot
    override val durationSlots: Int = block.durationSlots
    val animationKey: Any =
      listOf(block.startSlot, block.endSlot, block.title, block.org, block.status, block.isOverlap)
  }

  data class Advisory(val advisory: LivePermitAdvisory) : PermitGridColumnItem {
    override val startSlot: Int = advisory.startSlot
    override val endSlot: Int = advisory.endSlot
    override val durationSlots: Int = advisory.durationSlots
    val animationKey: Any = listOf(advisory.startSlot, advisory.endSlot, advisory.message)
  }
}

private fun PermitGridColumnItem.subtract(
  reservedSlots: List<IntRange>
): List<PermitGridColumnItem> {
  var remaining = listOf(startSlot to endSlot)
  for (slots in reservedSlots) {
    val reservedStart = slots.first
    val reservedEnd = slots.last + 1
    remaining = remaining.flatMap { (start, end) ->
      if (start >= reservedEnd || end <= reservedStart) {
        listOf(start to end)
      } else {
        buildList {
          if (start < reservedStart) {
            add(start to minOf(end, reservedStart))
          }
          if (end > reservedEnd) {
            add(maxOf(start, reservedEnd) to end)
          }
        }
      }
    }
  }
  return remaining.mapNotNull { (start, end) -> copyWithSlots(start, end) }
}

private fun PermitGridColumnItem.copyWithSlots(
  startSlot: Int,
  endSlot: Int,
): PermitGridColumnItem? {
  if (startSlot >= endSlot) return null
  return when (this) {
    is PermitGridColumnItem.Permit -> this
    is PermitGridColumnItem.Block ->
      copy(block = block.copy(startSlot = startSlot, endSlot = endSlot))
    is PermitGridColumnItem.Advisory ->
      copy(advisory = advisory.copy(startSlot = startSlot, endSlot = endSlot))
  }
}

@Composable
private inline fun <T> withDensity(block: Density.() -> T): T {
  val density = LocalDensity.current
  return with(density) { block() }
}

/** Draws a dashed "now" indicator line at the current time, animated in left-to-right. */
@Composable
private fun Modifier.nowIndicator(selectedDate: LocalDate, itemHeight: Dp): Modifier {
  val now = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }
  val isToday = selectedDate == now.date
  if (!isToday) return this

  val nowOffsetPx = withDensity { ((now.hour + now.minute / 60f) * itemHeight).toPx() }
  val lineColor = MaterialTheme.colorScheme.primary
  val strokePx = withDensity { 2.dp.toPx() }
  val dashPx = withDensity { 6.dp.toPx() }
  val gapPx = withDensity { 4.dp.toPx() }

  var previousDate by remember { mutableStateOf(selectedDate) }
  val progress = remember { Animatable(if (isToday) 1f else 0f) }
  LaunchedEffect(selectedDate) {
    val dateChanged = selectedDate != previousDate
    previousDate = selectedDate
    if (dateChanged) {
      progress.snapTo(0f)
      delay(150L.milliseconds)
      progress.animateTo(1f, tween(400))
    } else {
      progress.snapTo(1f)
    }
  }

  return drawWithContent {
    drawContent()
    val endX = size.width * progress.value
    drawLine(
      color = lineColor,
      start = Offset(0f, nowOffsetPx),
      end = Offset(endX, nowOffsetPx),
      strokeWidth = strokePx,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx)),
    )
  }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PermitEvent(
  fieldName: String,
  index: Int,
  event: Reserved,
  modifier: Modifier = Modifier,
  onEventClick: ((Reserved, orgVisible: Boolean) -> Unit)? = null,
) = SharedElementTransitionScope {
  val isOverlap = event.isOverlap
  val containerColor =
    if (event.isBlocked) {
      MaterialTheme.colorScheme.errorContainer
    } else if (isOverlap) {
      MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    }
  val isClickable = onEventClick != null && !isOverlap && !event.isBlocked
  var orgVisible by remember { mutableStateOf(false) }
  val sharedBoundsModifier =
    if (isClickable) {
      val sharedBoundsKey =
        PermitSharedElementKey(
          fieldName,
          index,
          event.title,
          event.timeRange,
          event.org,
          isOverlap = isOverlap,
        )
      Modifier.sharedBounds(
        sharedContentState = rememberSharedContentState(sharedBoundsKey),
        animatedVisibilityScope = requireAnimatedScope(Navigation),
        clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.medium),
        // Near-instant enter so grid appears immediately on back nav (target).
        // Default-speed exit so grid stays present as word animation source on forward nav.
        enter = fadeIn(tween(1)),
        exit = fadeOut(),
      )
    } else {
      Modifier
    }
  Surface(
    enabled = isClickable,
    onClick = { onEventClick!!(event, orgVisible) },
    modifier = modifier.fillMaxSize().padding(4.dp).then(sharedBoundsModifier),
    color = containerColor,
    shape = MaterialTheme.shapes.medium,
  ) {
    if (isOverlap) return@Surface
    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
      val textColor =
        if (event.isBlocked) {
          MaterialTheme.colorScheme.onErrorContainer
        } else {
          MaterialTheme.colorScheme.onSecondaryContainer
        }

      ReflowText(
        text = event.title,
        sharedElementKey = if (isClickable) "permit-${fieldName}-${index}" else null,
        sharedElementKeySuffix = "title",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
        color = textColor,
      )

      ReflowText(
        text = event.org,
        sharedElementKey = if (isClickable && orgVisible) "permit-${fieldName}-${index}" else null,
        modifier = Modifier.onPlaced { orgVisible = it.size.height > 0 && it.size.width > 0 },
        sharedElementKeySuffix = "org",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        overflow = TextOverflow.Ellipsis,
        color = textColor.copy(alpha = 0.5f),
      )
    }
  }
}
