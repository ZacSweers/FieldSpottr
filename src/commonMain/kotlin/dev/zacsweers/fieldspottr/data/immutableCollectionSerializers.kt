// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
class ImmutableListSerializer<T>(private val dataSerializer: KSerializer<T>) :
  KSerializer<ImmutableList<T>> {
  private class PersistentListDescriptor : SerialDescriptor by serialDescriptor<List<String>>() {
    override val serialName: String = "kotlinx.serialization.immutable.ImmutableList"
  }

  override val descriptor: SerialDescriptor = PersistentListDescriptor()

  override fun serialize(encoder: Encoder, value: ImmutableList<T>) {
    return ListSerializer(dataSerializer).serialize(encoder, value.toList())
  }

  override fun deserialize(decoder: Decoder): ImmutableList<T> {
    return ListSerializer(dataSerializer).deserialize(decoder).toPersistentList()
  }
}

@OptIn(ExperimentalSerializationApi::class)
class ImmutableSetSerializer<T>(private val dataSerializer: KSerializer<T>) :
  KSerializer<ImmutableSet<T>> {
  private class PersistentSetDescriptor : SerialDescriptor by serialDescriptor<Set<String>>() {
    override val serialName: String = "kotlinx.serialization.immutable.ImmutableSet"
  }

  override val descriptor: SerialDescriptor = PersistentSetDescriptor()

  override fun serialize(encoder: Encoder, value: ImmutableSet<T>) {
    return SetSerializer(dataSerializer).serialize(encoder, value.toSet())
  }

  override fun deserialize(decoder: Decoder): ImmutableSet<T> {
    return SetSerializer(dataSerializer).deserialize(decoder).toPersistentSet()
  }
}

@OptIn(ExperimentalSerializationApi::class)
class ImmutableMapSerializer<K, V>(
  private val keySerializer: KSerializer<K>,
  private val valueSerializer: KSerializer<V>,
) : KSerializer<ImmutableMap<K, V>> {
  private class PersistentMapDescriptor :
    SerialDescriptor by serialDescriptor<Map<String, String>>() {
    override val serialName: String = "kotlinx.serialization.immutable.ImmutableMap"
  }

  override val descriptor: SerialDescriptor = PersistentMapDescriptor()

  override fun serialize(encoder: Encoder, value: ImmutableMap<K, V>) {
    return MapSerializer(keySerializer, valueSerializer).serialize(encoder, value.toMap())
  }

  override fun deserialize(decoder: Decoder): ImmutableMap<K, V> {
    return MapSerializer(keySerializer, valueSerializer).deserialize(decoder).toPersistentMap()
  }
}
