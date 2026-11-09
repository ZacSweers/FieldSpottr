// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class PayloadCodecTest {

  @Test
  fun `every status stores a fixed name and cause and reads back from them`() {
    // Literal names, since rows one store or app version wrote must read back in every other
    val stored =
      listOf(
        StoredStatus(OutboxStatus.Queued, name = "queued", cause = null),
        StoredStatus(OutboxStatus.Sending, name = "sending", cause = null),
        StoredStatus(OutboxStatus.Failed("boom"), name = "failed", cause = "boom"),
        StoredStatus(OutboxStatus.Sent, name = "sent", cause = null),
        StoredStatus(OutboxStatus.Discarded, name = "discarded", cause = null),
      )

    for ((status, name, cause) in stored) {
      assertThat(status.storedName).isEqualTo(name)
      assertThat(status.storedCause).isEqualTo(cause)
      assertThat(OutboxStatus(name, cause)).isEqualTo(status)
    }
  }

  @Test
  fun `an unknown stored status reads as failed`() {
    assertThat(OutboxStatus("archived"))
      .isEqualTo(OutboxStatus.Failed("Unknown stored status archived"))
  }

  /** A status and the name and cause a durable store writes for it. */
  private data class StoredStatus(val status: OutboxStatus, val name: String, val cause: String?)
}
