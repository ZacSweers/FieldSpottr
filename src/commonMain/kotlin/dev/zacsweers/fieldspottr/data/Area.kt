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
          Field("Soccer-01A East 6th Street", "Track Field 1", "Track"),
          Field("Soccer-01 East 6th Street", "Track Field 2", "Track"),
          Field("Soccer-01B East 6th Street", "Track Field 3", "Track"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Field 6",
        listOf(
          Field("Baseball-06", "Field 6 (Baseball)", "Field 6"),
          Field("Softball-05", "Field 6 (Baseball)", "Field 6"),
          Field("Soccer-03 Houston St & FDR", "Field 6 (Outfield)", "Field 6"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Grand Street",
        listOf(
          Field("Soccer-02 Grand Street", "Grand Street", "Grand Street"),
          Field("Grand Street Mini Field-Soccer-03", "Grand Street Mini Field", "Grand Street"),
        ),
        "ERP",
      ),
      FieldGroup("Pier 42", listOf(Field("Pier 42 - Soccer-01", "Pier 42", "Pier 42")), "ERP"),
      FieldGroup(
        "Corlears Hook",
        listOf(
          Field("Corlears Hook Park - Soccer-01", "Corlears Hook (Soccer)", "Corlears Hook"),
          Field("Corlears Hook Park - Softball-01", "Corlears Hook (Softball)", "Corlears Hook"),
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
          Field("Softball-01", "Softball Field 1", "Baruch"),
          Field("Football-01", "Soccer Field 1", "Baruch"),
          Field("Football-02", "Soccer Field 2", "Baruch"),
          Field("Softball-02", "Softball Field 2", "Baruch"),
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
          Field("Soccer-01", "Peter's Field (Soccer)", "Peter's Field"),
          Field("Softball-01", "Peter's Field (Softball)", "Peter's Field"),
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
