// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import kotlinx.serialization.Serializable

/** A change that adds [tag] to a tag list, the payload of most optimistic store tests. */
@Serializable data class Add(val tag: String)

/**
 * Adds the tag once, as the store requires: a tag already there leaves the list as it is, so a
 * change applied again changes nothing. Makes a list from nothing.
 */
fun addOnce(current: List<String>?, add: Add): List<String> {
  val tags = current.orEmpty()
  return if (add.tag in tags) {
    tags
  } else {
    tags + add.tag
  }
}

/**
 * Appends the tag, which is not idempotent, so a change a view applied twice shows. A store reports
 * such an apply once per set, which the tests that use it expect.
 */
fun append(current: List<String>?, add: Add): List<String> = current.orEmpty() + add.tag
