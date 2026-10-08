package com.minashin1120.voxcribe.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApkDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var output: File
    private lateinit var downloader: ApkDownloader
    private val payload = ByteArray(4 * 1024 * 1024 + 17) { (it % 251).toByte() }
    private val rangeRequests = AtomicInteger()
    private val fullRequests = AtomicInteger()
    private val allRanges = CountDownLatch(4)
    private var mode = "ranges"

    @Before fun setUp() {
        directory = Files.createTempDirectory("apk-download-test").toFile()
        output = File(directory, "update.apk")
        downloader = ApkDownloader(OkHttpClient(), "ApkDownloaderTest")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("identity", request.headers["Accept-Encoding"])
                if (request.url.encodedPath == "/redirect") {
                    return MockResponse.Builder().code(302).addHeader("Location", "/apk").build()
                }
                if (request.method == "HEAD") {
                    return MockResponse.Builder().code(if (mode == "head-failure") 405 else 200)
                        .addHeader("Content-Length", payload.size)
                        .addHeader("Accept-Ranges", if (mode == "single") "none" else "bytes")
                        .addHeader("ETag", "\"fixed-apk\"").build()
                }
                val range = request.headers["Range"]
                if (range == null) {
                    fullRequests.incrementAndGet()
                    val bytes = if (mode == "truncated") payload.copyOf(100) else payload
                    return MockResponse.Builder().body(Buffer().write(bytes)).build()
                }
                rangeRequests.incrementAndGet()
                assertEquals("\"fixed-apk\"", request.headers["If-Range"])
                allRanges.countDown()
                assertTrue("4範囲が同時に要求されること", allRanges.await(5, TimeUnit.SECONDS))
                if (mode == "ignored" || mode == "truncated") {
                    return MockResponse.Builder().body("range unsupported").build()
                }
                val (start, end) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                return MockResponse.Builder().code(206)
                    .addHeader("Content-Range", if (mode == "wrong-range") "bytes 0-0/${payload.size}"
                        else "bytes $start-$end/${payload.size}")
                    .addHeader("ETag", "\"fixed-apk\"")
                    .body(Buffer().write(payload, start, end - start + 1))
                    .apply { if (mode == "stalled") bodyDelay(30, TimeUnit.SECONDS) }
                    .build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    @Test fun parallelDownloadReconstructsApkAndUpdatesEveryRead() = runBlocking {
        val values = mutableListOf<Float>()
        downloader.download(server.url("/redirect").toString(), payload.size.toLong(), output) { values.add(it) }
        assertArrayEquals(payload, output.readBytes())
        assertEquals(4, rangeRequests.get())
        assertEquals(0, fullRequests.get())
        // 4MB / 64KB以上。進捗を整数%や時間で間引かない。
        assertTrue(values.size >= payload.size / (64 * 1024))
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, values.last())
        assertFalse(File(directory, "update.apk.part").exists())
    }

    @Test fun rangeIgnoredFallsBackToFullDownload() = runBlocking {
        mode = "ignored"
        downloadAndCheck()
        assertEquals(4, rangeRequests.get())
        assertEquals(1, fullRequests.get())
    }

    @Test fun wrongContentRangeFallsBackWithoutMixingData() = runBlocking {
        mode = "wrong-range"
        downloadAndCheck()
        assertEquals(1, fullRequests.get())
    }

    @Test fun rangeUnsupportedUsesSingleDownload() = runBlocking {
        mode = "single"
        downloadAndCheck()
        assertEquals(0, rangeRequests.get())
        assertEquals(1, fullRequests.get())
    }

    @Test fun headUnsupportedUsesSingleDownload() = runBlocking {
        mode = "head-failure"
        downloadAndCheck()
        assertEquals(0, rangeRequests.get())
        assertEquals(1, fullRequests.get())
    }

    @Test fun incompleteApkIsNotPublished() = runBlocking {
        mode = "truncated"
        var failed = false
        try {
            downloadAndCheck()
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(output.exists())
        assertFalse(File(directory, "update.apk.part").exists())
    }

    @Test fun cancellationClosesStalledRangesAndDeletesPartialFile() = runBlocking {
        mode = "stalled"
        val task = async(Dispatchers.Default) { downloadAndCheck() }
        withContext(Dispatchers.IO) { assertTrue(allRanges.await(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { task.cancelAndJoin() }
        assertFalse(output.exists())
        assertFalse(File(directory, "update.apk.part").exists())
        assertEquals(0, fullRequests.get())
    }

    private suspend fun downloadAndCheck() {
        downloader.download(server.url("/apk").toString(), payload.size.toLong(), output) {}
        assertArrayEquals(payload, output.readBytes())
    }
}
