// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlin.io.encoding.Base64
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json

/**
 * Encodes a payload to one column and back, like SQLDelight's ColumnAdapter. The store knows rows,
 * the codec knows the payload format, and the repository knows neither.
 */
interface PayloadCodec<E> {
  /** [payload] as the text a store keeps in its row. */
  fun encode(payload: E): String

  /** The payload that [encode] turned into [encoded]. */
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

/**
 * The payload format durable store sets use by default: JSON that ignores keys the payload class no
 * longer has, so a row stored before a field was removed still decodes.
 */
internal val DefaultPayloadFormat: SerialFormat = Json { ignoreUnknownKeys = true }

/**
 * [decode] of [encoded], or the failure it threw. A durable store decodes each row on its own this
 * way, so one row that does not decode never hides the others.
 */
internal fun <E> PayloadCodec<E>.decodeCatching(encoded: String): Result<E> =
  try {
    Result.success(decode(encoded))
  } catch (e: Exception) {
    Result.failure(e)
  }

/**
 * The rows a durable store left out of its reads because their payloads do not decode, and the
 * listener that hears of each one, as [OutboxStore.onUndecodable] describes. A row met while no
 * listener is set waits here until one is. It holds one entry per such row, since rows this process
 * writes always decode, and [clear] empties it once the store deleted every row. A row id never
 * comes back once its row is deleted, so an id recorded by a read that overlapped the delete only
 * skips nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class UndecodableRows {
  private val state = AtomicReference(State())

  /** Whether row [id] is known not to decode, so a read can skip it without decoding. */
  operator fun contains(id: String): Boolean = id in state.load().ids

  /**
   * Records row [id], which a read failed to decode with [cause]. A row not recorded before goes to
   * the listener, or waits for one.
   */
  fun found(id: String, cause: Throwable) {
    while (true) {
      val current = state.load()
      if (id in current.ids) {
        return
      }
      val listener = current.listener
      val waiting =
        if (listener == null) {
          current.waiting.putting(EntryId(id), cause)
        } else {
          current.waiting
        }
      if (state.compareAndSet(current, State(current.ids.adding(id), listener, waiting))) {
        listener?.deliver(EntryId(id), cause)
        return
      }
    }
  }

  /**
   * Sets [report] as the listener in place of any other, and hands it the rows that waited for one.
   * Closing the result removes it, unless a newer listener replaced it.
   */
  fun listen(report: (id: EntryId, cause: Throwable) -> Unit): AutoCloseable {
    val listener = Listener(report)
    val before = state.fetchAndUpdate { State(it.ids, listener, persistentMapOf()) }
    for ((id, cause) in before.waiting) {
      listener.deliver(id, cause)
    }
    return AutoCloseable {
      state.update { current ->
        if (current.listener === listener) {
          State(current.ids, listener = null, current.waiting)
        } else {
          current
        }
      }
    }
  }

  /** Forgets every row, after the store deleted them all. The listener stays. */
  fun clear() {
    state.update { State(listener = it.listener) }
  }

  /** A listener, compared by identity, so closing one never removes another. */
  private class Listener(private val report: (id: EntryId, cause: Throwable) -> Unit) {
    /**
     * Hands row [id] to [report]. A throw from [report] is dropped, so it never fails the read that
     * met the row, and each waiting row still gets its own call.
     */
    fun deliver(id: EntryId, cause: Throwable) {
      try {
        report(id, cause)
      } catch (e: Exception) {
        // The listener is the failure handler itself, so nothing is left to tell
      }
    }
  }

  /**
   * @property ids the rows known not to decode.
   * @property listener hears of each new such row, or null while nothing listens.
   * @property waiting the rows found while nothing listened, oldest first, with their decode
   *   errors.
   */
  private class State(
    val ids: PersistentSet<String> = persistentHashSetOf(),
    val listener: Listener? = null,
    val waiting: PersistentMap<EntryId, Throwable> = persistentMapOf(),
  )
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
