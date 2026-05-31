package com.rillmaster.pipanel

import java.util.UUID

data class PiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "pi",
    val password: String = "",
    val piHolePassword: String = ""
)
