// Copyright (C) 2022 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.retained

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.remember

/**
 * Remember the value produced by [init].
 *
 * It behaves similarly to [remember], but the stored value will survive configuration changes, such
 * as a screen rotation.
 *
 * You can use it with a value stored inside [androidx.compose.runtime.mutableStateOf].
 *
 * This differs from `rememberSaveable` by not being tied to Android bundles or parcelable. You
 * should take care to ensure that the state computed by [init] does not capture anything that is
 * not safe to persist across reconfiguration, such as Navigators. The same caveats of
 * `rememberSaveable` also still apply (i.e. do not retain Android Contexts, Views, etc).
 *
 * However, it does not participate in saved instance state either, so care should be taken to
 * choose the right retention mechanism for your use case. Consider the below two examples.
 *
 * The first case will retain `state` across configuration changes but will _not_ survive process
 * death.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var state by rememberRetained { mutableStateOf(CounterState(0)) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = state.copy(count = state.count + 1)
 *       is CounterEvent.Decrement -> state = state.copy(count = state.count - 1)
 *     }
 *   }
 * }
 * ```
 *
 * This second case will retain `count` across configuration changes _and_ survive process death.
 * However, it only works with primitives or `Parcelable` state types.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var count by rememberSaveable { mutableStateOf(0) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = count++
 *       is CounterEvent.Decrement -> state = count--
 *     }
 *   }
 * }
 * ```
 *
 * @param inputs A set of inputs such that, when any of them have changed, will cause the state to
 *   reset and [init] to be rerun
 * @param key An optional key to be used as a key for the saved value. If not provided we use the
 *   automatically generated by the Compose runtime which is unique for the every exact code
 *   location in the composition tree
 * @param init A factory function to create the initial value of this state
 */
@Composable
public fun <T : Any> rememberRetained(vararg inputs: Any?, key: String? = null, init: () -> T): T {
  val registry = LocalRetainedStateRegistry.current
  // Short-circuit no-ops
  if (registry === NoOpRetainedStateRegistry) {
    return when (inputs.size) {
      0 -> remember(init)
      1 -> remember(inputs[0], init)
      2 -> remember(inputs[0], inputs[1], init)
      3 -> remember(inputs[0], inputs[1], inputs[2], init)
      else -> remember(keys = inputs, init)
    }
  }

  val compositeKey = currentCompositeKeyHash
  // key is the one provided by the user or the one generated by the compose runtime
  val finalKey =
    if (!key.isNullOrEmpty()) {
      key
    } else {
      compositeKey.toString(MaxSupportedRadix)
    }

  val canRetainChecker = LocalCanRetainChecker.current ?: rememberCanRetainChecker()
  val holder =
    remember(canRetainChecker) {
      // value is restored using the registry or created via [init] lambda
      val restored = registry.consumeValue(finalKey) as? RetainableHolder.Value<*>
      val finalValue = restored?.value ?: init()
      val finalInputs = restored?.inputs ?: inputs
      RetainableHolder(registry, canRetainChecker, finalKey, finalValue, finalInputs)
    }
  val value = holder.getValueIfInputsAreEqual(inputs) ?: init()
  SideEffect { holder.update(registry, finalKey, value, inputs) }
  @Suppress("UNCHECKED_CAST") return value as T
}

/** The maximum radix available for conversion to and from strings. */
private const val MaxSupportedRadix = 36

private class RetainableHolder<T>(
  private var registry: RetainedStateRegistry?,
  private var canRetainChecker: CanRetainChecker,
  private var key: String,
  private var value: T,
  private var inputs: Array<out Any?>,
) : RetainedValueProvider, RememberObserver {
  private var entry: RetainedStateRegistry.Entry? = null

  fun update(registry: RetainedStateRegistry?, key: String, value: T, inputs: Array<out Any?>) {
    var entryIsOutdated = false
    if (this.registry !== registry) {
      this.registry = registry
      entryIsOutdated = true
    }
    if (this.key != key) {
      this.key = key
      entryIsOutdated = true
    }
    this.value = value
    this.inputs = inputs
    if (entry != null && entryIsOutdated) {
      entry?.unregister()
      entry = null
      register()
    }
  }

  private fun register() {
    val registry = registry
    require(entry == null) { "entry($entry) is not null" }
    if (registry != null) {
      entry = registry.registerValue(key, this)
    }
  }

  /** Value provider called by the registry. */
  override fun invoke(): Any =
    Value(value = requireNotNull(value) { "Value should be initialized" }, inputs = inputs)

  fun saveIfRetainable() {
    // If the value is a RetainedStateRegistry, we need to take care to retain it.
    // First we tell it to saveAll, to retain it's values. Then we need to tell the host
    // registry to retain the child registry.
    if (value is RetainedStateRegistry) {
      (value as RetainedStateRegistry).saveAll()
      registry?.saveValue(key)
    }

    if (registry != null && !canRetainChecker.canRetain(registry!!)) {
      entry?.unregister()
    }
  }

  override fun onRemembered() {
    register()
  }

  override fun onForgotten() {
    saveIfRetainable()
  }

  override fun onAbandoned() {
    saveIfRetainable()
  }

  fun getValueIfInputsAreEqual(inputs: Array<out Any?>): T? {
    return value.takeIf { inputs.contentEquals(this.inputs) }
  }

  class Value<T>(val value: T, val inputs: Array<out Any?>)
}
