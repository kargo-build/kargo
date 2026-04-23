/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import com.example.Konfig
import java.util.Properties

fun main() {
    println("version: ${Konfig.VERSION}; id: ${Konfig.ID}")
    val props = Properties()
    props.load(object {}.javaClass.classLoader.getResourceAsStream("generated.properties"))
    println("greeting: ${props.getProperty("greeting")}")
}
