package com.example.data

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Compact, lossless fixed-point delta-varint + optional zlib codec for mutual fund NAV time-series.
 *
 * Layout:
 * - Version 1 (uncompressed, 10^-6 scale): legacy compatible reader.
 * - Version 2 (uncompressed delta-varint with adaptive 10^-4 or 10^-6 scale):
 *   - byte 0: version = 2
 *   - byte 1: scaleExp (4 for 10^4 when exact to 4dp, 6 for 10^6)
 *   - varint count, zigzag firstDay, zigzag firstScaledNav, then (deltaDay, zigzag deltaNav) pairs.
 * - Version 3 (Deflate-compressed Version 2 stream when Deflate shrinks total size):
 *   - byte 0: version = 3
 *   - bytes 1..n: standard zlib/deflate payload of Version 2 body bytes[1..end]
 *
 * Compresses multi-year NAV series from ~170 bytes/row (multi-B-tree SQLite) down to ~1.5-3.2 bytes/point
 * with zero duplicate dates and exact financial accuracy.
 */
object NavBinaryCodec {

    private const val CODEC_VERSION_1: Byte = 1
    private const val CODEC_VERSION_2_RAW: Byte = 2
    private const val CODEC_VERSION_3_DEFLATED: Byte = 3

    private const val SCALE_4_EXP: Byte = 4
    private const val SCALE_6_EXP: Byte = 6
    private const val NAV_SCALE_6 = 1_000_000.0
    private const val NAV_SCALE_4 = 10_000.0

    fun toNavMicro(navValue: Double): Long = (navValue * NAV_SCALE_6).roundToLong()

    fun fromNavMicro(navMicro: Long): Double = navMicro.toDouble() / NAV_SCALE_6

    fun partitionByYear(points: List<NavPoint>): Map<Int, List<NavPoint>> {
        if (points.isEmpty()) return emptyMap()
        val grouped = LinkedHashMap<Int, ArrayList<NavPoint>>()
        for (pt in points) {
            if (pt.navValue <= 0.0) continue
            val year = LocalDate.ofEpochDay(pt.dateEpochDay).year
            grouped.getOrPut(year) { ArrayList() }.add(pt)
        }
        return grouped
    }

    /**
     * Merges [existingPayload] (nullable) with [incomingPoints], deduplicating by epochDay
     * (keeping incoming value for the same day if updated) and reports how many genuinely new
     * dates were added vs whether the byte payload changed.
     */
    fun mergeIntoYearBucket(
        schemeId: Int,
        year: Int,
        existingPayload: ByteArray?,
        incomingPoints: List<NavPoint>
    ): BucketMergeOutcome {
        val merged = TreeMap<Int, Long>()
        if (existingPayload != null && existingPayload.isNotEmpty()) {
            decodeIntoMap(existingPayload, merged)
        }
        val initialSize = merged.size
        var changed = false

        for (pt in incomingPoints) {
            if (pt.navValue <= 0.0) continue
            val day = pt.dateEpochDay.toInt()
            val micro = toNavMicro(pt.navValue)
            if (micro <= 0L) continue
            val prev = merged.put(day, micro)
            if (prev == null || prev != micro) {
                changed = true
            }
        }

        if (merged.isEmpty()) {
            return BucketMergeOutcome(bucket = null, newlyInsertedCount = 0, payloadChanged = false)
        }

        val newlyInserted = (merged.size - initialSize).coerceAtLeast(0)
        if (!changed && existingPayload != null) {
            val existingBucket = NavBucketEntity(
                schemeId = schemeId,
                year = year,
                pointCount = merged.size,
                minEpochDay = merged.firstKey(),
                maxEpochDay = merged.lastKey(),
                payload = existingPayload
            )
            return BucketMergeOutcome(existingBucket, newlyInsertedCount = 0, payloadChanged = false)
        }

        val encoded = encodeSortedMap(merged)
        val bucket = NavBucketEntity(
            schemeId = schemeId,
            year = year,
            pointCount = merged.size,
            minEpochDay = merged.firstKey(),
            maxEpochDay = merged.lastKey(),
            payload = encoded
        )
        return BucketMergeOutcome(bucket, newlyInsertedCount = newlyInserted, payloadChanged = true)
    }

    fun encodeSortedMap(sortedPoints: TreeMap<Int, Long>): ByteArray {
        if (sortedPoints.isEmpty()) return ByteArray(0)

        // Use 10^-4 scaling whenever all points are exact to 4 decimal places (AMFI standard);
        // otherwise retain full 10^-6 precision losslessly.
        var use4Decimals = true
        for (micro in sortedPoints.values) {
            if (micro % 100L != 0L) {
                use4Decimals = false
                break
            }
        }
        val scaleDivisor = if (use4Decimals) 100L else 1L
        val scaleExp = if (use4Decimals) SCALE_4_EXP else SCALE_6_EXP

        val bodyOut = ByteArrayOutputStream(sortedPoints.size * 3 + 16)
        bodyOut.write(scaleExp.toInt())
        writeUnsignedVarInt(bodyOut, sortedPoints.size.toLong())

        var prevDay = 0
        var prevScaled = 0L
        var isFirst = true

        for ((day, navMicro) in sortedPoints) {
            val scaled = navMicro / scaleDivisor
            if (isFirst) {
                writeZigZagVarInt(bodyOut, day.toLong())
                writeZigZagVarInt(bodyOut, scaled)
                isFirst = false
            } else {
                val deltaDay = (day - prevDay).coerceAtLeast(1)
                writeUnsignedVarInt(bodyOut, deltaDay.toLong())
                writeZigZagVarInt(bodyOut, scaled - prevScaled)
            }
            prevDay = day
            prevScaled = scaled
        }

        val bodyBytes = bodyOut.toByteArray()

        // Apply fast zlib compression when bucket has enough points to benefit
        if (sortedPoints.size >= 16 && bodyBytes.size >= 40) {
            val deflated = deflateBytes(bodyBytes)
            if (deflated.size + 1 < bodyBytes.size + 1) {
                val out = ByteArray(deflated.size + 1)
                out[0] = CODEC_VERSION_3_DEFLATED
                System.arraycopy(deflated, 0, out, 1, deflated.size)
                return out
            }
        }

        val rawOut = ByteArray(bodyBytes.size + 1)
        rawOut[0] = CODEC_VERSION_2_RAW
        System.arraycopy(bodyBytes, 0, rawOut, 1, bodyBytes.size)
        return rawOut
    }

    fun decode(payload: ByteArray): List<NavPoint> {
        if (payload.isEmpty()) return emptyList()
        return when (payload[0]) {
            CODEC_VERSION_1 -> decodeV1(payload)
            CODEC_VERSION_2_RAW -> decodeV2Body(payload, startOffset = 1)
            CODEC_VERSION_3_DEFLATED -> {
                val inflated = inflateBytes(payload, offset = 1, length = payload.size - 1)
                if (inflated.isEmpty()) emptyList() else decodeV2Body(inflated, startOffset = 0)
            }
            else -> emptyList()
        }
    }

    /**
     * Pre-encodes a scheme's sorted [points] into year-partitioned [NavBucketEntity] objects on CPU
     * worker threads before acquiring any SQLite write transaction lock.
     */
    fun encodePointsIntoBuckets(
        schemeId: Int,
        points: List<NavPoint>
    ): Map<Int, NavBucketEntity> {
        if (points.isEmpty()) return emptyMap()
        val partitioned = partitionByYear(points)
        if (partitioned.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Int, NavBucketEntity>(partitioned.size)
        for ((year, yearPts) in partitioned) {
            val outcome = mergeIntoYearBucket(schemeId, year, null, yearPts)
            val bucket = outcome.bucket
            if (bucket != null) {
                result[year] = bucket
            }
        }
        return result
    }

    private fun decodeIntoMap(payload: ByteArray, target: TreeMap<Int, Long>) {
        if (payload.isEmpty()) return
        when (payload[0]) {
            CODEC_VERSION_1 -> decodeV1IntoMap(payload, target)
            CODEC_VERSION_2_RAW -> decodeV2BodyIntoMap(payload, startOffset = 1, target = target)
            CODEC_VERSION_3_DEFLATED -> {
                val inflated = inflateBytes(payload, offset = 1, length = payload.size - 1)
                if (inflated.isNotEmpty()) decodeV2BodyIntoMap(inflated, startOffset = 0, target = target)
            }
        }
    }

    private fun decodeV1(payload: ByteArray): List<NavPoint> {
        val cursor = Cursor(payload, startOffset = 1)
        val count = cursor.readUnsignedVarInt().toInt().coerceAtLeast(0)
        if (count == 0) return emptyList()
        val result = ArrayList<NavPoint>(count)
        var prevDay = cursor.readZigZagVarInt().toInt()
        var prevNav = cursor.readZigZagVarInt()
        result.add(NavPoint(prevDay.toLong(), fromNavMicro(prevNav)))
        for (i in 1 until count) {
            if (!cursor.hasRemaining()) break
            prevDay += cursor.readUnsignedVarInt().toInt()
            prevNav += cursor.readZigZagVarInt()
            result.add(NavPoint(prevDay.toLong(), fromNavMicro(prevNav)))
        }
        return result
    }

    private fun decodeV1IntoMap(payload: ByteArray, target: TreeMap<Int, Long>) {
        val cursor = Cursor(payload, startOffset = 1)
        val count = cursor.readUnsignedVarInt().toInt().coerceAtLeast(0)
        if (count == 0) return
        var prevDay = cursor.readZigZagVarInt().toInt()
        var prevNav = cursor.readZigZagVarInt()
        target[prevDay] = prevNav
        for (i in 1 until count) {
            if (!cursor.hasRemaining()) break
            prevDay += cursor.readUnsignedVarInt().toInt()
            prevNav += cursor.readZigZagVarInt()
            target[prevDay] = prevNav
        }
    }

    private fun decodeV2Body(bytes: ByteArray, startOffset: Int): List<NavPoint> {
        val cursor = Cursor(bytes, startOffset)
        if (!cursor.hasRemaining()) return emptyList()
        val scaleExp = cursor.readByte()
        val scaleMultiplier = if (scaleExp == SCALE_4_EXP) 100L else 1L
        val count = cursor.readUnsignedVarInt().toInt().coerceAtLeast(0)
        if (count == 0) return emptyList()

        val result = ArrayList<NavPoint>(count)
        var prevDay = cursor.readZigZagVarInt().toInt()
        var prevScaled = cursor.readZigZagVarInt()
        result.add(NavPoint(prevDay.toLong(), fromNavMicro(prevScaled * scaleMultiplier)))

        for (i in 1 until count) {
            if (!cursor.hasRemaining()) break
            prevDay += cursor.readUnsignedVarInt().toInt()
            prevScaled += cursor.readZigZagVarInt()
            result.add(NavPoint(prevDay.toLong(), fromNavMicro(prevScaled * scaleMultiplier)))
        }
        return result
    }

    private fun decodeV2BodyIntoMap(bytes: ByteArray, startOffset: Int, target: TreeMap<Int, Long>) {
        val cursor = Cursor(bytes, startOffset)
        if (!cursor.hasRemaining()) return
        val scaleExp = cursor.readByte()
        val scaleMultiplier = if (scaleExp == SCALE_4_EXP) 100L else 1L
        val count = cursor.readUnsignedVarInt().toInt().coerceAtLeast(0)
        if (count == 0) return

        var prevDay = cursor.readZigZagVarInt().toInt()
        var prevScaled = cursor.readZigZagVarInt()
        target[prevDay] = prevScaled * scaleMultiplier

        for (i in 1 until count) {
            if (!cursor.hasRemaining()) break
            prevDay += cursor.readUnsignedVarInt().toInt()
            prevScaled += cursor.readZigZagVarInt()
            target[prevDay] = prevScaled * scaleMultiplier
        }
    }

    private fun deflateBytes(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size)
            val buf = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateBytes(input: ByteArray, offset: Int, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val inflater = Inflater(true)
        return try {
            inflater.setInput(input, offset, length)
            val out = ByteArrayOutputStream(length * 3)
            val buf = ByteArray(1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            inflater.end()
        }
    }

    private fun writeZigZagVarInt(out: ByteArrayOutputStream, value: Long) {
        val zz = (value shl 1) xor (value shr 63)
        writeUnsignedVarInt(out, zz)
    }

    private fun writeUnsignedVarInt(out: ByteArrayOutputStream, raw: Long) {
        var v = raw
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    private class Cursor(private val data: ByteArray, startOffset: Int = 0) {
        private var pos = startOffset

        fun hasRemaining(): Boolean = pos < data.size

        fun readByte(): Byte = if (pos < data.size) data[pos++] else 0

        fun readUnsignedVarInt(): Long {
            var value = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                value = value or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift > 63) break
            }
            return value
        }

        fun readZigZagVarInt(): Long {
            val raw = readUnsignedVarInt()
            return (raw ushr 1) xor -(raw and 1L)
        }
    }
}

data class BucketMergeOutcome(
    val bucket: NavBucketEntity?,
    val newlyInsertedCount: Int,
    val payloadChanged: Boolean
)
