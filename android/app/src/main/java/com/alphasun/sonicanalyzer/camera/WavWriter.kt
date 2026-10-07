package com.alphasun.sonicanalyzer.camera

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 事件音频落盘：PCM → 16bit WAV
 *
 * Web 版用 `OfflineAudioContext` 编码成 Blob，Android 侧没有等价且可靠的做法，
 * 直接按 WAV 规范手写头最稳（不依赖任何三方库，也不会有 resample 偏差）。
 *
 * WAV 结构：
 *   "RIFF" + (36 + dataLen) u32 + "WAVE"
 *   "fmt " + 16 u32 + PCM=1 u16 + ch u16 + sr u32 + byteRate u32 + blockAlign u16 + bits=16 u16
 *   "data" + dataLen u32 + 样本
 */
object WavWriter {

    fun write(file: File, pcm: FloatArray, sampleRate: Int, channels: Int): Boolean {
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0) return false
        return try {
            file.parentFile?.mkdirs()
            val ch = channels.coerceIn(1, 2)
            val bytesPerSample = 2
            val blockAlign = ch * bytesPerSample
            val dataLen = pcm.size * bytesPerSample
            val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataLen)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16)                       // fmt chunk size
            buf.putShort(1)                      // format = PCM
            buf.putShort(ch.toShort())
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * blockAlign)   // byte rate
            buf.putShort(blockAlign.toShort())
            buf.putShort(16)                     // bits per sample
            buf.put("data".toByteArray())
            buf.putInt(dataLen)
            for (v in pcm) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                buf.putShort(s.toShort())
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(buf.array())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 把分散在多帧的 PCM 拼成一段 */
    fun concat(chunks: List<FloatArray>): FloatArray {
        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]
        var n = 0
        for (c in chunks) n += c.size
        val out = FloatArray(n)
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
        return out
    }
}
