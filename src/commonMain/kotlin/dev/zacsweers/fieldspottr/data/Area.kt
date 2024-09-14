// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class Areas(
  @Serializable(with = ImmutableListSerializer::class) val entries: ImmutableList<Area>,
  val version: Int = VERSION,
) {
  val groups by lazy { entries.flatMap { it.fieldGroups }.associateBy { it.name } }

  companion object {
    const val VERSION = 1
    val default by lazy { buildDefaultAreas() }
  }
}

@Serializable
@Immutable
data class Area(
  val areaName: String,
  val displayName: String,
  val csvUrl: String,
  @Serializable(with = ImmutableListSerializer::class) val fieldGroups: ImmutableList<FieldGroup>,
) {
  val fieldMappings: ImmutableMap<String, Field> by lazy {
    fieldGroups
      .flatMap(FieldGroup::fields)
      .map { field -> field.name to field }
      .associate { (first, second) -> first to second }
      .toImmutableMap()
  }
}

@DslMarker annotation class AreaDSL

@AreaDSL
fun buildAreas(block: AreasBuilder.() -> Unit): Areas {
  val builder = AreasBuilder()
  builder.block()
  val areas = builder.build()
  return Areas(areas.toImmutableList())
}

class AreasBuilder {
  private val areas = mutableListOf<Area>()

  @AreaDSL
  fun area(name: String, displayName: String, csvUrl: String, block: AreaBuilder.() -> Unit) {
    val builder = AreaBuilder(name, displayName, csvUrl)
    builder.block()
    areas.add(builder.build())
  }

  fun build(): List<Area> {
    return areas
  }

  class AreaBuilder(val name: String, val displayName: String, val csvUrl: String) {
    private val fieldGroups = mutableListOf<FieldGroup>()

    @AreaDSL
    fun group(name: String, block: FieldGroupBuilder.() -> Unit) {
      val builder = FieldGroupBuilder(name, this.name)
      builder.block()
      fieldGroups.add(builder.build())
    }

    fun build(): Area {
      return Area(name, displayName, csvUrl, fieldGroups.toImmutableList())
    }

    class FieldGroupBuilder(val name: String, val areaName: String) {
      private val fields = mutableListOf<Field>()

      @AreaDSL
      fun field(csvName: String, displayName: String, sharedFields: Set<String> = setOf(csvName)) {
        fields.add(Field(csvName, displayName, this.name, sharedFields.toImmutableSet()))
      }

      fun build(): FieldGroup {
        return FieldGroup(name, fields.toImmutableList(), areaName)
      }
    }
  }
}

fun buildDefaultAreas(): Areas {
  return buildAreas {
    area(
      name = "ERP",
      displayName = "East River Park",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M144/csv",
    ) {
      group("Track") {
        field(
          csvName = "Soccer-01A East 6th Street",
          displayName = "North Half",
          sharedFields = setOf("Track field", "track field 1a"),
        )
        field(
          csvName = "Soccer-01 East 6th Street",
          displayName = "Whole Field",
          sharedFields = setOf("Track field"),
        )
        field(
          csvName = "Soccer-01B East 6th Street",
          displayName = "South Half",
          sharedFields = setOf("Track field", "track field 1b"),
        )
      }
      group("Field 6") {
        field(csvName = "Baseball-06", displayName = "Baseball", sharedFields = setOf("field6"))
        field(
          csvName = "Soccer-03 Houston St & FDR",
          displayName = "Outfield",
          sharedFields = setOf("field6"),
        )
      }
      group("Field 2 (Grand Street)") {
        field(csvName = "Soccer-02 Grand Street", displayName = "Whole Field")
      }
    }
    area(
      name = "Baruch",
      displayName = "Baruch Playground",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M165/csv",
    ) {
      group("Baruch") {
        field(csvName = "Softball-01", displayName = "Softball 1", sharedFields = setOf("field1"))
        field(csvName = "Football-01", displayName = "Soccer 1", sharedFields = setOf("field1"))
        field(csvName = "Football-02", displayName = "Soccer 2", sharedFields = setOf("field2"))
        field(csvName = "Softball-02", displayName = "Softball 2", sharedFields = setOf("field2"))
      }
    }
    area(
      name = "Corlears Hook",
      displayName = "Corlears Hook",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M017/csv",
    ) {
      group("Corlears Hook") {
        field(csvName = "Soccer-01", displayName = "Soccer")
        field(csvName = "Softball-01", displayName = "Softball")
      }
    }
    area(
      name = "Pier 42",
      displayName = "Pier 42",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M369/csv",
    ) {
      group("Pier 42") { field(csvName = "Soccer-01", displayName = "Soccer") }
    }
    area(
      name = "Peter's Field",
      displayName = "Peter's Field",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M227/csv",
    ) {
      group("Peter's Field") {
        field(csvName = "Soccer-01", displayName = "Soccer", sharedFields = setOf("petersfield"))
        field(
          csvName = "Softball-01",
          displayName = "Softball",
          sharedFields = setOf("petersfield"),
        )
      }
    }
    area(
      name = "McCarren",
      displayName = "McCarren Park",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/B058/csv",
    ) {
      group(name = "McCarren Track") { field(csvName = "Soccer-01", displayName = "Soccer") }
    }
    area(
      name = "Bushwick Inlet Park",
      displayName = "Bushwick Inlet Park",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/B529/csv",
    ) {
      group("Bushwick Inlet Park") {
        field(
          csvName = "Soccer-01A",
          displayName = "West Half",
          sharedFields = setOf("bushwick inlet field", "field 1a"),
        )
        field(
          csvName = "Soccer-01",
          displayName = "Whole Field",
          sharedFields = setOf("bushwick inlet field"),
        )
        field(
          csvName = "Soccer-01B",
          displayName = "East Half",
          sharedFields = setOf("bushwick inlet field", "field 1b"),
        )
      }
    }
  }
}

@Serializable
@Immutable
data class FieldGroup(
  val name: String,
  @Serializable(with = ImmutableListSerializer::class) val fields: ImmutableList<Field>,
  val area: String,
)

@Serializable
@Immutable
data class Field(
  val name: String,
  val displayName: String,
  val group: String,
  /**
   * Shared fields. Field names can be anything, just as long as the unique keys unique within their
   * group and fields use the same keys.
   */
  @Serializable(with = ImmutableSetSerializer::class)
  val sharedFields: ImmutableSet<String> = persistentSetOf(name),
) {
  fun overlapsWith(other: Field): Boolean {
    return sharedFields.intersect(other.sharedFields).isNotEmpty()
  }
}
