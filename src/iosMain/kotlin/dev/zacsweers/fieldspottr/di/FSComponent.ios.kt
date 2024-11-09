// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import kotlin.reflect.KClass
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
interface IosFSComponent : FSComponent {
  companion object {
    fun create() = IosFSComponent::class.createComponent()
  }
}

/**
 * The `actual fun` will be generated for each iOS specific target. See [MergeComponent] for more
 * details.
 */
@MergeComponent.CreateComponent expect fun KClass<IosFSComponent>.createComponent(): IosFSComponent
