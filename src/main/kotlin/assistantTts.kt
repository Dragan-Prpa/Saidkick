package com.example

import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

object assistantTts {
    private val logger = Logger.getInstance(assistantTts::class.java)
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun speakStreaming(text: String, config: AssistantConfig): Boolean {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) return false

        val apiKey = config.elevenLabsApiKey
        val voiceId = config.elevenLabsVoiceId
        if (apiKey.isNullOrBlank() || voiceId.isNullOrBlank()) {
            logger.warn("ElevenLabs TTS is not configured. Set ELEVENLABS_API_KEY and ELEVENLABS_VOICE_ID.")
            return false
        }

        val outputFormat = config.elevenLabsOutputFormat.ifBlank { "pcm_24000" }
        if (!outputFormat.startsWith("pcm_")) {
            logger.warn("Unsupported ElevenLabs output format '$outputFormat'. Use a pcm_* format for streaming playback.")
            return false
        }

        val sampleRate = outputFormat.substringAfter('_', "24000").toFloatOrNull() ?: 24000f
        val requestBody = buildJsonBody(normalizedText, config.elevenLabsModel)
        val request = HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=$outputFormat",
                ),
            )
            .header("xi-api-key", apiKey)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }.getOrElse { error ->
            logger.warn("ElevenLabs streaming request failed", error)
            return false
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().readAllBytes().toString(Charsets.UTF_8)
            logger.warn("ElevenLabs streaming returned ${response.statusCode()}: $errorBody")
            return false
        }

        response.body().use { inputStream ->
            playPcmStream(inputStream, sampleRate)
        }

        return true
    }

    private fun playPcmStream(inputStream: InputStream, sampleRate: Float) {
        val audioFormat = AudioFormat(sampleRate, 16, 1, true, false)
        val lineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)
        val line = AudioSystem.getLine(lineInfo) as SourceDataLine

        line.open(audioFormat)
        line.start()

        writeSilence(line, audioFormat, 320)

        inputStream.use { stream ->
            val buffer = ByteArray(4096)
            var pendingByte: Byte? = null
            var bytesRead = stream.read(buffer)
            while (bytesRead >= 0) {
                if (bytesRead > 0) {
                    pendingByte = writeAlignedPcm(line, buffer, bytesRead, pendingByte)
                }
                bytesRead = stream.read(buffer)
            }

            if (pendingByte != null) {
                line.write(byteArrayOf(pendingByte, 0), 0, 2)
            }
        }

        writeSilence(line, audioFormat, 160)
        line.drain()
        line.stop()
        line.close()
    }

    private fun writeAlignedPcm(
        line: SourceDataLine,
        buffer: ByteArray,
        bytesRead: Int,
        pendingByte: Byte?,
    ): Byte? {
        val frameSize = 2
        var startIndex = 0
        var bytesToWrite = bytesRead

        if (pendingByte != null) {
            val framed = byteArrayOf(pendingByte, buffer[0])
            line.write(framed, 0, framed.size)
            startIndex = 1
            bytesToWrite -= 1
        }

        val alignedBytes = bytesToWrite - (bytesToWrite % frameSize)
        if (alignedBytes > 0) {
            line.write(buffer, startIndex, alignedBytes)
        }

        return if ((bytesToWrite % frameSize) == 1) buffer[startIndex + alignedBytes] else null
    }

    private fun writeSilence(line: SourceDataLine, format: AudioFormat, durationMs: Int) {
        val bytesPerFrame = format.frameSize
        val frames = (format.frameRate * durationMs / 1000.0).toInt().coerceAtLeast(0)
        val silence = ByteArray(frames * bytesPerFrame)
        if (silence.isNotEmpty()) {
            line.write(silence, 0, silence.size)
        }
    }

    private fun buildJsonBody(text: String, modelId: String): String {
        return """
            {
              "text": ${toJsonString(text)},
              "model_id": ${toJsonString(modelId)}
            }
        """.trimIndent()
    }

    private fun toJsonString(value: String): String {
        val escaped = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
        return "\"$escaped\""
    }
}