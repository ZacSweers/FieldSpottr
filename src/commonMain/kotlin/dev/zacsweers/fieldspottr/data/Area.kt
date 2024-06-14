// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

internal enum class Area(
  val areaName: String,
  val csvUrl: String,
  val fieldGroups: List<FieldGroup>,
) {
  ERP(
    "ERP",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M144/csv",
    listOf(
      FieldGroup(
        "Track",
        listOf(
          Field("Soccer-01A East 6th Street", "Field 1", "Track"),
          Field("Soccer-01 East 6th Street", "Field 2", "Track"),
          Field("Soccer-01B East 6th Street", "Field 3", "Track"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Field 6",
        listOf(
          Field("Baseball-06", "Baseball 6", "Field 6"),
          Field("Softball-05", "Baseball 5", "Field 6"),
          Field("Soccer-03 Houston St & FDR", "Outfield", "Field 6"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Grand Street",
        listOf(
          Field("Soccer-02 Grand Street", "Field", "Grand Street"),
          Field("Grand Street Mini Field-Soccer-03", "Mini Field", "Grand Street"),
        ),
        "ERP",
      ),
      FieldGroup("Pier 42", listOf(Field("Pier 42 - Soccer-01", "Field", "Pier 42")), "ERP"),
      FieldGroup(
        "Corlears Hook",
        listOf(
          Field("Corlears Hook Park - Soccer-01", "Soccer", "Corlears Hook"),
          Field("Corlears Hook Park - Softball-01", "Softball", "Corlears Hook"),
        ),
        "ERP",
      ),
    ),
  ),
  BARUCH(
    "Baruch",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M165/csv",
    listOf(
      FieldGroup(
        "Baruch",
        listOf(
          Field("Softball-01", "Softball 1", "Baruch"),
          Field("Football-01", "Soccer 1", "Baruch"),
          Field("Football-02", "Soccer 2", "Baruch"),
          Field("Softball-02", "Softball 2", "Baruch"),
        ),
        "Baruch",
      )
    ),
  ),
  PETERS_FIELD(
    "Peter's Field",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M227/csv",
    listOf(
      FieldGroup(
        "Peter's Field",
        listOf(
          Field("Soccer-01", "Soccer", "Peter's Field"),
          Field("Softball-01", "Softball", "Peter's Field"),
        ),
        "Peter's Field",
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
  }
}
