package com.autoshipper.fulfillment.handler.webhook

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockHttpServletRequest

@ExtendWith(MockitoExtension::class)
class CachingRequestWrapperTest {

    @Test
    fun `body is readable multiple times via getInputStream`() {
        val body = """{"order_id": 12345, "total_price": "49.99"}"""
        val request = MockHttpServletRequest().apply {
            setContent(body.toByteArray(Charsets.UTF_8))
        }

        val wrapper = CachingRequestWrapper(request)

        val firstRead = String(wrapper.inputStream.readAllBytes(), Charsets.UTF_8)
        val secondRead = String(wrapper.inputStream.readAllBytes(), Charsets.UTF_8)

        assert(firstRead == body) { "First read should match original body" }
        assert(secondRead == body) { "Second read should match original body" }
    }

    @Test
    fun `byte content is preserved exactly`() {
        val binaryContent = byteArrayOf(0x00, 0x01, 0x7F, -0x80, -0x01, 0x41, 0x42, 0x43)
        val request = MockHttpServletRequest().apply {
            setContent(binaryContent)
        }

        val wrapper = CachingRequestWrapper(request)

        val readBytes = wrapper.inputStream.readAllBytes()
        assert(readBytes.contentEquals(binaryContent)) { "Byte content should be preserved exactly" }
    }

    @Test
    fun `getCachedBody returns same bytes as original body`() {
        val body = """{"line_items": [{"product_id": 1, "quantity": 2}]}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request = MockHttpServletRequest().apply {
            setContent(bodyBytes)
        }

        val wrapper = CachingRequestWrapper(request)

        val cachedBody = wrapper.getCachedBody()
        assert(cachedBody.contentEquals(bodyBytes)) { "getCachedBody should return same bytes" }
    }

    @Test
    fun `getCachedBody returns same bytes after getInputStream has been called`() {
        val body = "test body content"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request = MockHttpServletRequest().apply {
            setContent(bodyBytes)
        }

        val wrapper = CachingRequestWrapper(request)

        // Read via getInputStream first
        wrapper.inputStream.readAllBytes()

        // getCachedBody should still return the same bytes
        val cachedBody = wrapper.getCachedBody()
        assert(cachedBody.contentEquals(bodyBytes)) { "getCachedBody should return same bytes after stream read" }
    }

    @Test
    fun `getReader returns correct content`() {
        val body = """{"customer": {"email": "test@example.com"}}"""
        val request = MockHttpServletRequest().apply {
            setContent(body.toByteArray(Charsets.UTF_8))
        }

        val wrapper = CachingRequestWrapper(request)

        val readerContent = wrapper.reader.readText()
        assert(readerContent == body) { "getReader should return correct content" }
    }

    @Test
    fun `getReader is readable after getInputStream`() {
        val body = "reader after stream"
        val request = MockHttpServletRequest().apply {
            setContent(body.toByteArray(Charsets.UTF_8))
        }

        val wrapper = CachingRequestWrapper(request)

        // Exhaust the input stream
        wrapper.inputStream.readAllBytes()

        // Reader should still work
        val readerContent = wrapper.reader.readText()
        assert(readerContent == body) { "getReader should work after getInputStream was read" }
    }

    @Test
    fun `empty body is handled correctly`() {
        val request = MockHttpServletRequest().apply {
            setContent(ByteArray(0))
        }

        val wrapper = CachingRequestWrapper(request)

        val cachedBody = wrapper.getCachedBody()
        assert(cachedBody.isEmpty()) { "Empty body should return empty byte array" }

        val streamContent = wrapper.inputStream.readAllBytes()
        assert(streamContent.isEmpty()) { "Empty body stream should return empty byte array" }

        val readerContent = wrapper.reader.readText()
        assert(readerContent.isEmpty()) { "Empty body reader should return empty string" }
    }
}
