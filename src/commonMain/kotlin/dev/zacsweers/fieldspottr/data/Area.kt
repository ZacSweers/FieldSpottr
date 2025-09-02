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
    fun group(name: String, location: Location, block: FieldGroupBuilder.() -> Unit) {
      val builder = FieldGroupBuilder(name, this.name, location)
      builder.block()
      fieldGroups.add(builder.build())
    }

    fun build(): Area {
      return Area(name, displayName, csvUrl, fieldGroups.toImmutableList())
    }

    class FieldGroupBuilder(val name: String, val areaName: String, val location: Location) {
      private val fields = mutableListOf<Field>()

      @AreaDSL
      fun field(csvName: String, displayName: String, sharedFields: Set<String> = setOf(csvName)) {
        fields.add(Field(csvName, displayName, this.name, sharedFields.toImmutableSet()))
      }

      fun build(): FieldGroup {
        return FieldGroup(name, fields.toImmutableList(), areaName, location)
      }
    }
  }
}

fun buildDefaultAreas(): Areas {
  return buildAreas {
    area(
      name = "Baruch",
      displayName = "Baruch Playground",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M165/csv",
    ) {
      group(
        name = "Baruch",
        location =
          Location(
            "https://maps.app.goo.gl/S6x4PgTCGucMKnB9A",
            "https://maps.apple.com/?address=Baruch%20Pl,%20New%20York,%20NY%2010002,%20United%20States&auid=13062604862514247086&ll=40.717599,-73.976666&lsp=9902&q=Baruch%20Playground",
          ),
      ) {
        field(csvName = "Softball-01", displayName = "Softball 1", sharedFields = setOf("field1"))
        field(csvName = "Football-01", displayName = "Soccer 1", sharedFields = setOf("field1"))
        field(csvName = "Football-02", displayName = "Soccer 2", sharedFields = setOf("field2"))
        field(csvName = "Softball-02", displayName = "Softball 2", sharedFields = setOf("field2"))
      }
    }
    area(
      name = "ERP",
      displayName = "East River Park",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M144/csv",
    ) {
      group(
        name = "Track",
        location =
          Location(
            "https://maps.app.goo.gl/bFmVPr5st28os4vQA",
            "https://maps.apple.com/?address=E%20River%20Esplanade%20%26%20FDR%20Drive%0ANew%20York,%20NY%20%2010002%0AUnited%20States&auid=16002436924869229563&ll=40.721770,-73.973812&lsp=9902&q=East%20River%20Park%20Running%20Track",
          ),
      ) {
        field(
          csvName = "Soccer-01A East 6th Street",
          displayName = "North Half",
          sharedFields = setOf("Track field", "track field 1a"),
        )
        field(
          csvName = "Soccer-04 East 6th Street",
          displayName = "Whole Field",
          sharedFields = setOf("Track field"),
        )
        field(
          csvName = "Soccer-01B East 6th Street",
          displayName = "South Half",
          sharedFields = setOf("Track field", "track field 1b"),
        )
      }
      group(
        name = "Field 6",
        location =
          Location(
            "https://maps.app.goo.gl/KD27oHCF6Jw1UhX58",
            "https://maps.apple.com/?address=John%20V.%20Lindsay%20East%20River%20Park,%20E%20River%20Esplanade,%20New%20York,%20NY%20%2010002,%20United%20States&auid=2997997111374685654&ll=40.719770,-73.974155&lsp=9902&q=John%20V.%20Lindsay%20East%20River%20Park%20Baseball%20Fields",
          ),
      ) {
        field(csvName = "Baseball-06", displayName = "Baseball", sharedFields = setOf("field6"))
        field(
          csvName = "Soccer-03 Houston St & FDR",
          displayName = "Outfield",
          sharedFields = setOf("field6"),
        )
      }
      group(
        name = "Grand Street (Field 2)",
        location =
          Location(
            "https://www.google.com/maps/place/40°42'44.2\"N+73°58'37.4\"W/@40.712286,-73.9792062,17z/data=!3m1!4b1!4m4!3m3!8m2!3d40.712286!4d-73.977041",
            "https://maps.apple.com/?address=John%20V.%20Lindsay%20East%20River%20Park,%20New%20York,%20NY%20%2010002,%20United%20States&ll=40.712206,-73.976992&q=Dropped%20Pin",
          ),
      ) {
        field(
          csvName = "Grand Street - Softball-01",
          displayName = "Softball (south))",
          sharedFields = setOf("field1"),
        )
        field(
          csvName = "Grand Street - Soccer 01",
          displayName = "Whole Field",
          sharedFields = setOf("field1"),
        )
        field(
          csvName = "Grand Street - Softball-02",
          displayName = "Softball (north)",
          sharedFields = setOf("field1"),
        )
      }
      // TODO the site says 03 but the CSV says 02?
      group(
        name = "Grand Street Mini Field (Field 3)",
        location =
          Location(
            "https://www.google.com/maps/place/East+River+Park+*+Grand+Street+Mini+Field/@40.7135106,-73.9766765,19.39z/data=!4m6!3m5!1s0x89c25b0020f63825:0x94cfd8ce67954ddd!8m2!3d40.7133945!4d-73.9768339!16s%2Fg%2F11yft3l7v2",
            "https://maps.apple.com/place?coordinate=40.713506,-73.976811&name=Marked%20Location",
          ),
      ) {
        field(csvName = "Grand Street Mini Field-Soccer-02", displayName = "Whole Field")
      }
    }
    area(
      name = "Corlears Hook",
      displayName = "Corlears Hook",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M017/csv",
    ) {
      group(
        name = "Corlears Hook",
        location =
          Location(
            "https://maps.app.goo.gl/qhdRxmus8UPUtSyY8",
            "https://maps.apple.com/?address=397%20FDR%20Drive,%20New%20York,%20NY%2010002,%20United%20States&auid=16313010153387216666&ll=40.711739,-73.979789&lsp=9902&q=Corlears%20Hook%20Park",
          ),
      ) {
        field(csvName = "Soccer-01", displayName = "Soccer")
        field(csvName = "Softball-01", displayName = "Softball")
      }
    }
    area(
      name = "Pier 42",
      displayName = "Pier 42",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M369/csv",
    ) {
      group(
        name = "Pier 42",
        location =
          Location(
            "https://maps.app.goo.gl/d9Qi6Dqu8z5yPdU56",
            "https://maps.apple.com/?address=New%20York,%20NY%2010002,%20United%20States&auid=14094921999338982991&ll=40.709947,-73.982427&lsp=9902&q=Pier%2042",
          ),
      ) {
        field(csvName = "Soccer-01", displayName = "Soccer")
      }
    }
    area(
      name = "Peter's Field",
      displayName = "Peter's Field",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M227/csv",
    ) {
      group(
        name = "Peter's Field",
        location =
          Location(
            "https://maps.app.goo.gl/Q1rftzTaKiVHRNDz6",
            "https://maps.apple.com/?address=301%20E%2020th%20St,%20New%20York,%20NY%20%2010003,%20United%20States&auid=7985386323298652825&ll=40.736111,-73.981667&lsp=9902&q=Peter's%20Field",
          ),
      ) {
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
      group(
        name = "McCarren Track",
        location =
          Location(
            "https://maps.app.goo.gl/4oHKgX7kkxTPcwWS7",
            "https://maps.apple.com/?address=Lorimer%20Street,%20Union%20%26%20Driggs%20Avenue,%20Brooklyn,%20NY%2011211,%20United%20States&auid=2152782516168223158&ll=40.720052,-73.951718&lsp=9902&q=McCarren%20Park%20Track",
          ),
      ) {
        field(csvName = "Soccer-01", displayName = "Soccer")
      }
    }
    area(
      name = "Bushwick Inlet Park",
      displayName = "Bushwick Inlet Park",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/B529/csv",
    ) {
      group(
        name = "Bushwick Inlet Park",
        location =
          Location(
            "https://maps.app.goo.gl/P1J6DhgdAVmG8Syq6",
            "https://maps.apple.com/?address=86%20Kent%20Ave,%20Brooklyn,%20NY%2011249,%20United%20States&auid=17553726733896206163&ll=40.722201,-73.961238&lsp=9902&q=Bushwick%20Inlet%20Park",
          ),
      ) {
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
    area(
      name = "Murphy Brothers Playground",
      displayName = "Murphy Brothers Playground",
      csvUrl = "https://www.nycgovparks.org/permits/field-and-court/issued/M059/csv",
    ) {
      group(
        name = "Murphy Brothers Playground",
        location =
          Location(
            "https://maps.app.goo.gl/ji38JTioKTi1aCZu8",
            "https://maps.apple.com/?address=729%20E%2016th%20St,%20New%20York,%20NY%20%2010009,%20United%20States&auid=6845864669219202528&ll=40.730238,-73.973458&lsp=9902&q=Murphy%20Brothers%20Playground",
          ),
      ) {
        field(
          csvName = "Tim McGinn Fields-Softball-01",
          displayName = "West Softball",
          sharedFields = setOf("murphy whole field"),
        )
        field(
          csvName = "Tim McGinn Fields-Soccer-01",
          displayName = "Whole Field",
          sharedFields = setOf("murphy whole field"),
        )
        field(
          csvName = "Tim McGinn Fields-Softball-02",
          displayName = "East Softball",
          sharedFields = setOf("murphy whole field"),
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
  val location: Location,
)

@Serializable @Immutable data class Location(val gmaps: String, val amaps: String)

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
