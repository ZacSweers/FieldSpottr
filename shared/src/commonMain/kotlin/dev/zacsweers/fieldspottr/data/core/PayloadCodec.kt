// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat

/**
 * Encodes a payload to one column and back, like SQLDelight's ColumnAdapter. The store knows rows,
 * the codec knows the payload format, and the repository knows neither.
 */
interface PayloadCodec<E> {
  fun encode(payload: E): String

  fun decode(encoded: String): E
}

/** A codec for any kotlinx.serialization format. A [BinaryFormat] is stored as Base64. */
fun <E> KSerializer<E>.asCodec(format: SerialFormat): PayloadCodec<E> {
  val serializer = this
  return when (format) {
    is StringFormat ->
      object : PayloadCodec<E> {
        override fun encode(payload: E) = format.encodeToString(serializer, payload)

        override fun decode(encoded: String) = format.decodeFromString(serializer, encoded)
      }
    is BinaryFormat ->
      object : PayloadCodec<E> {
        override fun encode(payload: E) =
          Base64.encode(format.encodeToByteArray(serializer, payload))

        override fun decode(encoded: String) =
          format.decodeFromByteArray(serializer, Base64.decode(encoded))
      }
    else -> error("$format is neither a StringFormat nor a BinaryFormat")
  }
}

/** How every durable store spells a status, so rows written by one store read back in another. */
val OutboxStatus.storedName: String
  get() =
    when (this) {
      OutboxStatus.Queued -> "queued"
      OutboxStatus.Sending -> "sending"
      is OutboxStatus.Failed -> "failed"
      OutboxStatus.Sent -> "sent"
      OutboxStatus.Discarded -> "discarded"
    }

/** The failure reason a durable store keeps beside the status. Null for every status but Failed. */
val OutboxStatus.storedCause: String?
  get() = (this as? OutboxStatus.Failed)?.cause

/**
 * The status a durable store wrote as [storedName], with [cause] for a failed row. A name this
 * version does not know, such as one a newer version wrote, reads as Failed, so the row stays
 * visible and can be retried or discarded.
 */
fun OutboxStatus(storedName: String, cause: String? = null): OutboxStatus =
  when (storedName) {
    "queued" -> OutboxStatus.Queued
    "sending" -> OutboxStatus.Sending
    "failed" -> OutboxStatus.Failed(cause.orEmpty())
    "sent" -> OutboxStatus.Sent
    "discarded" -> OutboxStatus.Discarded
    else -> OutboxStatus.Failed("Unknown stored status $storedName")
  }
