package com.example.demo

import build.kargo.ktor.client.native.Native
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private val httpClient = HttpClient(Native) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
        })
    }
}

fun main() {
    embeddedServer(CIO, port = 3000) {
        routing {
            get("/"){
                val response = httpClient.request("https://api.restful-api.dev/objects") {
                    method = HttpMethod.Get
                    contentType(ContentType.Application.Json)
                }.body<String>()
                call.respondText(response, ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}
