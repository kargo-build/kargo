package com.example.demo

import build.kargo.samples.greeter.Greeter
import co.touchlab.kermit.Logger

fun main() {
    Logger.i { Greeter.hello("World")}
}
