// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

internal enum class Area(
  val areaName: String,
  val displayName: String,
  val csvUrl: String,
  val fieldGroups: List<FieldGroup>,
) {
  ERP(
    "ERP",
    displayName = "East River Park",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M144/csv",
    listOf(
      FieldGroup(
        "Track",
        listOf(
          Field(
            "Soccer-01A East 6th Street",
            "North Half",
            "Track",
            sharedFields = setOf("Track field", "track field 1a"),
          ),
          Field(
            "Soccer-01 East 6th Street",
            "Whole Field",
            "Track",
            sharedFields = setOf("Track field"),
          ),
          Field(
            "Soccer-01B East 6th Street",
            "South Half",
            "Track",
            sharedFields = setOf("Track field", "track field 1b"),
          ),
        ),
        "ERP",
      ),
      FieldGroup(
        "Field 6",
        listOf(
          Field("Baseball-06", "Baseball", "Field 6", sharedFields = setOf("field6")),
          Field("Soccer-03 Houston St & FDR", "Outfield", "Field 6", sharedFields = setOf("field6")),
        ),
        "ERP",
      ),
      FieldGroup(
        "Grand Street",
        listOf(
          Field("Soccer-02 Grand Street", "Main Field", "Grand Street"),
          Field("Grand Street Mini Field-Soccer-03", "Mini Field", "Grand Street"),
        ),
        "ERP",
      ),
    ),
  ),
  BARUCH(
    "Baruch",
    displayName = "Baruch Playground",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M165/csv",
    listOf(
      FieldGroup(
        "Baruch",
        listOf(
          Field("Softball-01", "Softball 1", "Baruch", sharedFields = setOf("field1")),
          Field("Football-01", "Soccer 1", "Baruch", sharedFields = setOf("field1")),
          Field("Football-02", "Soccer 2", "Baruch", sharedFields = setOf("field2")),
          Field("Softball-02", "Softball 2", "Baruch", sharedFields = setOf("field2")),
        ),
        "Baruch",
      )
    ),
  ),
  CORLEARS(
    "Corlears Hook",
    displayName = "Corlears Hook",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M017/csv",
    listOf(
      FieldGroup(
        "Corlears Hook",
        listOf(
          Field("Soccer-01", "Soccer", "Corlears Hook"),
          Field("Softball-01", "Softball", "Corlears Hook"),
        ),
        "Corlears Hook",
      )
    ),
  ),
  PIER_42(
    "Pier 42",
    displayName = "Pier 42",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M369/csv",
    listOf(FieldGroup("Pier 42", listOf(Field("Soccer-01", "Soccer", "Pier 42")), "Pier 42")),
  ),
  PETERS_FIELD(
    "Peter's Field",
    displayName = "Peter's Field",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M227/csv",
    listOf(
      FieldGroup(
        "Peter's Field",
        listOf(
          Field("Soccer-01", "Soccer", "Peter's Field", sharedFields = setOf("petersfield")),
          Field("Softball-01", "Softball", "Peter's Field", sharedFields = setOf("petersfield")),
        ),
        "Peter's Field",
      )
    ),
  ),
  MCCARREN(
    "McCarren",
    displayName = "McCarren Park Track",
    "https://www.nycgovparks.org/permits/field-and-court/issued/B058/csv",
    listOf(
      FieldGroup(
        name = "McCarren Track",
        fields =
          listOf(Field(name = "Soccer-01", displayName = "Soccer", group = "McCarren Track")),
        area = "McCarren",
      )
    ),
  ),
  BIP(
    "Bushwick Inlet Park",
    displayName = "Bushwick Inlet Park",
    "https://www.nycgovparks.org/permits/field-and-court/issued/B529/csv",
    listOf(
      FieldGroup(
        "Bushwick Inlet Park",
        listOf(
          Field(
            "Soccer-01A",
            "West Half",
            "Bushwick Inlet Park",
            sharedFields = setOf("bushwick inlet field", "field 1a"),
          ),
          Field(
            "Soccer-01",
            "Whole Field",
            "Bushwick Inlet Park",
            sharedFields = setOf("bushwick inlet field"),
          ),
          Field(
            "Soccer-01B",
            "East Half",
            "Bushwick Inlet Park",
            sharedFields = setOf("bushwick inlet field", "field 1b"),
          ),
        ),
        "Bushwick Inlet Park",
      )
    ),
  );

  val fieldMappings: Map<String, Field> by lazy {
    fieldGroups
      .flatMap(FieldGroup::fields)
      .map { field -> field.name to field }
      .associate { it.first to it.second }
  }

  companion object {
    val groups = entries.flatMap { it.fieldGroups }.associateBy { it.name }
    val groupsByArea =
      groups.values.groupBy { group ->
        Area.entries.find { it.areaName == group.area }
          ?: error("Could not find area with name ${group.area}")
      }
  }
}
