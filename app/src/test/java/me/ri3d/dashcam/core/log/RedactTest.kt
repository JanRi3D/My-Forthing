package me.ri3d.dashcam.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactTest {

    @Test
    fun `masks string and number values of every sensitive key`() {
        val json = """{"msg_id":1,"token":7341,"aescode":"QUJD+/==","param":{"passwd":"geheim","ssid":"FORTHING-1"},""" +
            """"key":"00ff","access_token":"ya29.x","refresh_token":"1//r","idToken":"eyJ","password":"pw"}"""

        val out = redact(json)

        assertThat(out).isEqualTo(
            """{"msg_id":1,"token":"***","aescode":"***","param":{"passwd":"***","ssid":"FORTHING-1"},""" +
                """"key":"***","access_token":"***","refresh_token":"***","idToken":"***","password":"***"}""",
        )
    }

    @Test
    fun `masks the StartSession tokenNum and null or boolean values`() {
        assertThat(redact("""{"rval":0,"msg_id":1,"tokenNum":12,"param":{"token":null,"key":true,"aescode":false}}"""))
            .isEqualTo("""{"rval":0,"msg_id":1,"tokenNum":"***","param":{"token":"***","key":"***","aescode":"***"}}""")
    }

    @Test
    fun `does not unpack JSON double-encoded inside a string`() {
        val json = """{"param":"{\"token\":1}"}"""
        assertThat(redact(json)).isEqualTo(json)
    }

    @Test
    fun `masks object and array values as a whole`() {
        assertThat(redact("""{"key":{"n":"1","token":2},"x":[1],"token":[1,{"a":"]"}]}"""))
            .isEqualTo("""{"key":"***","x":[1],"token":"***"}""")
    }

    @Test
    fun `keeps escaped quotes inside masked strings and ignores look-alike keys`() {
        assertThat(redact("""{ "token" : "a\"b,c", "monkey":"k", "keyName":"v", "note":"say \"token\": 1" }"""))
            .isEqualTo("""{ "token" : "***", "monkey":"k", "keyName":"v", "note":"say \"token\": 1" }""")
    }

    @Test
    fun `handles truncated input and text without secrets`() {
        assertThat(redact("""{"rval":0,"aescode":"QUJDREVG""")).isEqualTo("""{"rval":0,"aescode":"***"""")
        assertThat(redact("""{"rval":-1}""")).isEqualTo("""{"rval":-1}""")
        assertThat(redact("")).isEmpty()
    }
}
