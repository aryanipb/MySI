package com.mysi.agent.web

/** Loads a saved page or API response from app/src/test/resources/web. Tests never hit the network. */
object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/web/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}
