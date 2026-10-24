// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PayloadCodecTest {

  @Serializable private data class Note(val id: Int, val text: String)

  @Test
  fun `a string format round trips`() {
    val codec = Note.serializer().asCodec(Json)
    val note = Note(1, "hello")

    assertThat(codec.decode(codec.encode(note))).isEqualTo(note)
  }

  @Test
  fun `every status round trips through its stored form`() {
    val statuses =
      listOf(
        OutboxStatus.Queued,
        OutboxStatus.Sending,
        OutboxStatus.Failed("boom"),
        OutboxStatus.Sent,
        OutboxStatus.Discarded,
      )

    for (status in statuses) {
      assertThat(OutboxStatus(status.storedName, status.storedCause)).isEqualTo(status)
    }
  }

  @Test
  fun `an unknown stored status reads as failed`() {
    assertThat(OutboxStatus("archived"))
      .isEqualTo(OutboxStatus.Failed("Unknown stored status archived"))
  }
}
