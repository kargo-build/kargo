/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.jakarta.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Jetty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    routing {
        get("/hello") {
            call.respondText("Hello")
        }
    }
}
