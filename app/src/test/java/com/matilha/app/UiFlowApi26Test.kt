package com.matilha.app

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mesma jornada no Android 8.0 (minSdk), para pegar APIs novas sem verificação de versão. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26], qualifiers = "w411dp-h891dp-xhdpi")
class UiFlowApi26Test : UiFlowTest()
