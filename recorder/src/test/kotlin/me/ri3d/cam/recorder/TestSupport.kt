package me.ri3d.cam.recorder

private object Fixtures

internal fun fixture(name: String): String =
    checkNotNull(Fixtures::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText().trim()

internal fun reply(name: String): RecorderReply = checkNotNull(RecorderReply.parse(fixture(name))) { name }
