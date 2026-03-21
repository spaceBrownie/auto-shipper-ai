package com.autoshipper.fulfillment.handler.webhook

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * An HttpServletRequestWrapper that caches the request body bytes on first read,
 * allowing them to be re-read for HMAC verification and subsequent Spring deserialization.
 */
class CachingRequestWrapper(request: HttpServletRequest) : HttpServletRequestWrapper(request) {

    private val cachedBody: ByteArray = request.inputStream.readAllBytes()

    fun getCachedBody(): ByteArray = cachedBody

    override fun getInputStream(): ServletInputStream {
        val byteArrayInputStream = ByteArrayInputStream(cachedBody)
        return object : ServletInputStream() {
            override fun read(): Int = byteArrayInputStream.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                byteArrayInputStream.read(b, off, len)

            override fun isFinished(): Boolean = byteArrayInputStream.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) {
                // No async support needed for webhook processing
            }
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))
}
