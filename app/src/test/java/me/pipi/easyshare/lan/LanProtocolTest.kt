package me.pipi.easyshare.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanProtocolTest {
    @Test
    fun `announcement round trip`() {
        val announcement = LanAnnouncement(
            id = "abc-123",
            name = "小米手机",
            brand = "Xiaomi",
            brandId = 30,
            port = 48889,
        )
        val json = LanProtocol.json.encodeToString(LanAnnouncement.serializer(), announcement)
        assertTrue(json.contains("\"proto\":\"airshare-lan-1\""))
        assertEquals(announcement, LanProtocol.json.decodeFromString(LanAnnouncement.serializer(), json))
    }

    @Test
    fun `prepare request with files round trip`() {
        val request = LanPrepareRequest(
            senderName = "OPPO",
            senderBrandId = 10,
            textContent = null,
            files = listOf(
                LanFileMeta("照片.jpg", "image/jpeg", 2048),
                LanFileMeta("文档.pdf", "application/pdf", 123456),
            ),
        )
        val json = LanProtocol.json.encodeToString(LanPrepareRequest.serializer(), request)
        val decoded = LanProtocol.json.decodeFromString(LanPrepareRequest.serializer(), json)
        assertEquals(request, decoded)
        assertEquals(2, decoded.files.size)
    }

    @Test
    fun `text-only prepare keeps text and empty files`() {
        val request = LanPrepareRequest(senderName = "A", senderBrandId = 0, textContent = "hello")
        val json = LanProtocol.json.encodeToString(LanPrepareRequest.serializer(), request)
        val decoded = LanProtocol.json.decodeFromString(LanPrepareRequest.serializer(), json)
        assertEquals("hello", decoded.textContent)
        assertTrue(decoded.files.isEmpty())
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val json = """{"proto":"airshare-lan-1","id":"x","name":"n","brand":"b","brandId":30,"port":1,"future":true}"""
        val decoded = LanProtocol.json.decodeFromString(LanAnnouncement.serializer(), json)
        assertEquals("x", decoded.id)
    }

    @Test
    fun `complete request round trip`() {
        val request = LanCompleteRequest(taskId = 42, canceled = true)
        val json = LanProtocol.json.encodeToString(LanCompleteRequest.serializer(), request)
        assertEquals(request, LanProtocol.json.decodeFromString(LanCompleteRequest.serializer(), json))
    }
}
