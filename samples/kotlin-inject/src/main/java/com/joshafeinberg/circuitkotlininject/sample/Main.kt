// Copyright (C) 2024 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.joshafeinberg.circuitkotlininject.sample

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.codegen.annotations.MergeCircuitComponent
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.foundation.di.CircuitComponent
import com.slack.circuit.foundation.di.circuitBuilder
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope

fun main() = application {
  val parentComponent = remember { ParentComponent::class.create() }
  val circuit = remember {
    val circuitComponent = AppScopeCircuitComponent::class.create(parentComponent)
    circuitComponent.circuitBuilder().build()
  }

  Window(onCloseRequest = ::exitApplication, title = "Sample") {
    CircuitCompositionLocals(circuit) { CircuitContent(MyScreen) }
  }
}

@Component
abstract class ParentComponent {
  @Provides
  fun providesString(): String {
    return "Injected String!"
  }
}

@MergeCircuitComponent<ParentComponent>(AppScope::class)
interface AppScopeCircuitComponent : CircuitComponent

@Scope annotation class AppScope

@CircuitInject(MyScreen::class, AppScope::class)
@Composable
fun MyScreen(state: MyScreen.State, modifier: Modifier = Modifier) {
  Text(state.visibleString, modifier = modifier)
}

data object MyScreen : Screen {
  data class State(val visibleString: String) : CircuitUiState
}

@CircuitInject(MyScreen::class, AppScope::class)
class MyScreenPresenter(
  private val injectedString: String,
  @Suppress("unused") @Assisted private val screen: MyScreen,
) : Presenter<MyScreen.State> {
  @Composable
  override fun present(): MyScreen.State {
    return MyScreen.State(injectedString)
  }
}