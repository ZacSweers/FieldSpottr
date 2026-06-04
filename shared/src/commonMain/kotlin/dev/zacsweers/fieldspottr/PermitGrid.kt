// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
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
import dev.zacsweers.fieldspottr.data.withOverlapsFrom
import dev.zacsweers.fieldspottr.theme.fsColorScheme
import dev.zacsweers.fieldspottr.util.AutoMeasureText
import dev.zacsweers.fieldspottr.util.ReflowText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val TIME_COLUMN_WEIGHT = 0.15f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PermitGrid(
  selectedGroup: String,
  permits: PermitState?,
  areas: Areas,
  selectedDate: LocalDate,
  modifier: Modifier = Modifier,
  liveAvailability: LiveGroupAvailability? = null,
  cornerSlot: (@Composable () -> Unit)? = null,
  onEventClick: (fieldName: String, index: Int, Reserved, orgVisible: Boolean) -> Unit =
    { _, _, _, _ ->
    },
) {
  val group = areas.groups[selectedGroup] ?: return
  val resolvedLiveAvailability =
    remember(group, liveAvailability) { liveAvailabilityForGrid(group, liveAvailability) }
  val numColumns = group.fields.size

  val columnWeight = (1f - TIME_COLUMN_WEIGHT) / numColumns
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

  val scrollState = rememberScrollState(initial = initialScrollPx)
  LaunchedEffect(permits) {
    if (permits == null) return@LaunchedEffect
    val earliestPermit =
      permits.fields.values
        .flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
        .minOrNull() ?: 8
    scrollState.animateScrollTo(density.run { (earliestPermit * itemHeight).roundToPx() })
  }
  val isScrolled by remember { derivedStateOf { scrollState.value > 0 } }

  Column(modifier) {
    // Names of the fields as a header
    Surface {
      Box {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
          verticalAlignment = CenterVertically,
        ) {
          if (cornerSlot == null) {
            Spacer(Modifier.weight(TIME_COLUMN_WEIGHT))
          } else {
            Box(Modifier.weight(TIME_COLUMN_WEIGHT)) { cornerSlot() }
          }
          for (columnNumber in 0..<numColumns) {
            val defaultTextStyle = MaterialTheme.typography.titleMedium
            val textAlign = TextAlign.Center
            AutoMeasureText(
              modifier = Modifier.weight(columnWeight).fillMaxWidth(),
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
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
          .verticalScroll(scrollState)
          .nowIndicator(selectedDate, itemHeight)
    ) {
      // Time column
      Column(Modifier.weight(TIME_COLUMN_WEIGHT)) {
        for (rowNumber in 0..<24) {
          Box(Modifier.height(itemHeight)) {
            // Time marker
            val adjustedTime = ((rowNumber) % 12).let { if (it == 0) 12 else it }
            val amPm = if (rowNumber < 12) "AM" else "PM"
            Text(
              "$adjustedTime $amPm",
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.align(TopEnd).padding(4.dp),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )
          }
        }
      }

      val fields = permits?.fields ?: PermitState.EMPTY.fields
      for (field in group.fields) {
        val fieldStates = fields[field] ?: FieldState.EMPTY
        PermitGridColumn(
          fieldName = field.displayName,
          fieldStates = fieldStates,
          liveField = resolvedLiveAvailability?.fields?.get(field),
          itemHeight = itemHeight,
          modifier = Modifier.weight(columnWeight),
          permits = permits,
          onEventClick = onEventClick,
        )
      }
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
    Box(Modifier.height(itemHeight * ((nextSlot - currentSlot) / 2f)).fillMaxWidth()) {
      if (nextSlot % 2 == 0) {
        HorizontalDivider(modifier = Modifier.align(BottomCenter))
      }
    }
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
  Box(Modifier.height(itemHeight * event.duration).fillMaxWidth()) {
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
    HorizontalDivider(modifier = Modifier.align(BottomCenter))
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

  Box(Modifier.height(itemHeight * (durationSlots / 2f)).fillMaxWidth()) {
    Box(
      Modifier.fillMaxSize().graphicsLayer {
        alpha = animProgress.value
        translationY = (1f - animProgress.value) * translationYPx
      }
    ) {
      content()
    }
    HorizontalDivider(modifier = Modifier.align(BottomCenter))
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
