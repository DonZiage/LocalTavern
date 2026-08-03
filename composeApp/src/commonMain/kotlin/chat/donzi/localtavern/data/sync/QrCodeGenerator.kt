/*
 * QR Code generator library (Kotlin, Multiplatform port)
 *
 * Ported from the Java implementation by Project Nayuki.
 * Copyright (c) Project Nayuki. (MIT License)
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */
package chat.donzi.localtavern.data.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A QR Code symbol, which is a type of two-dimension barcode.
 * Instances of this class represent an immutable square grid of dark and light
 * cells. Covers QR Code Model 2, versions 1..40, all error correction levels.
 *
 * The only entry point used by the app is [encodeText], which encodes a UTF-8
 * string (our pairing payloads are short URLs, well under the size limits).
 */
class QrCode private constructor(
    /** The version number of this QR Code, between 1 and 40 (inclusive). */
    val version: Int,
    /** The error correction level used in this QR Code. */
    val errorCorrectionLevel: Ecc,
    /** The index of the mask pattern used, between 0 and 7 (inclusive). */
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {
    /** The width and height of this QR Code, measured in modules, between 21 and 177. */
    val size: Int = modules.size

    /** Returns the color of the module at (x, y): false for light, true for dark. */
    fun getModule(x: Int, y: Int): Boolean =
        0 <= x && x < size && 0 <= y && y < size && modules[y][x]

    companion object {
        /** The minimum version number (1) supported in the QR Code Model 2 standard. */
        const val MIN_VERSION = 1

        /** The maximum version number (40) supported in the QR Code Model 2 standard. */
        const val MAX_VERSION = 40

        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            // Version: (note that index 0 is for padding, and is set to an illegal value)
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )

        private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )

        /**
         * Returns a QR Code representing the specified Unicode text string at the
         * specified error correction level. The smallest possible version is chosen.
         */
        fun encodeText(text: String, ecl: Ecc): QrCode {
            val segs = QrSegment.makeSegments(text)
            return encodeSegments(segs, ecl)
        }

        fun encodeSegments(segs: List<QrSegment>, ecl: Ecc): QrCode =
            encodeSegments(segs, ecl, MIN_VERSION, MAX_VERSION, -1, true)

        private fun encodeSegments(segs: List<QrSegment>, ecl: Ecc, minVersion: Int, maxVersion: Int, mask: Int, boostEcl: Boolean): QrCode {
            var ecl = ecl
            if (!(MIN_VERSION <= minVersion && minVersion <= maxVersion && maxVersion <= MAX_VERSION) || mask < -1 || mask > 7)
                throw IllegalArgumentException("Invalid value")

            // Find the minimal version number to use
            var version: Int
            var dataUsedBits = 0
            version = minVersion
            while (true) {
                val dataCapacityBits = getNumDataCodewords(version, ecl) * 8
                dataUsedBits = QrSegment.getTotalBits(segs, version)
                if (dataUsedBits != -1 && dataUsedBits <= dataCapacityBits) break
                if (version >= maxVersion) {
                    throw IllegalArgumentException("Segment too long: $dataUsedBits bits, max capacity $dataCapacityBits bits")
                }
                version++
            }

            // Increase the error correction level while the data still fits in the current version number
            for (newEcl in Ecc.entries) {  // From low to high
                if (boostEcl && dataUsedBits <= getNumDataCodewords(version, newEcl) * 8)
                    ecl = newEcl
            }

            // Concatenate all segments to create the data bit string
            val bb = BitBuffer()
            for (seg in segs) {
                bb.appendBits(seg.mode.modeBits, 4)
                bb.appendBits(seg.numChars, seg.mode.numCharCountBits(version))
                bb.appendData(seg.data)
            }

            // Add terminator and pad up to a byte if applicable
            val dataCapacityBits = getNumDataCodewords(version, ecl) * 8
            bb.appendBits(0, min(4, dataCapacityBits - bb.bitLength))
            bb.appendBits(0, (8 - bb.bitLength % 8) % 8)

            // Pad with alternating bytes until data capacity is reached
            var padByte = 0xEC
            while (bb.bitLength < dataCapacityBits) {
                bb.appendBits(padByte, 8)
                padByte = padByte xor (0xEC xor 0x11)
            }

            // Pack bits into bytes in big endian
            val dataCodewords = ByteArray(bb.bitLength / 8)
            for (i in 0 until bb.bitLength) {
                if (bb.getBit(i) != 0)
                    dataCodewords[i ushr 3] = (dataCodewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }

            // Create the QR Code object
            return QrCode.fromDataCodewords(version, ecl, dataCodewords, mask)
        }

        private fun getNumDataCodewords(ver: Int, ecl: Ecc): Int =
            getNumRawDataModules(ver) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver]

        private fun getNumRawDataModules(ver: Int): Int {
            if (ver < MIN_VERSION || ver > MAX_VERSION)
                throw IllegalArgumentException("Version number out of range")

            val size = ver * 4 + 17
            var result = size * size
            result -= 8 * 8 * 3
            result -= 15 * 2 + 1
            result -= (size - 16) * 2
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (numAlign - 1) * (numAlign - 1) * 25
                result -= (numAlign - 2) * 2 * 20
                if (ver >= 7)
                    result -= 6 * 3 * 2
            }
            return result
        }

        private fun reedSolomonComputeDivisor(degree: Int): ByteArray {
            if (degree < 1 || degree > 255)
                throw IllegalArgumentException("Degree out of range")
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = (reedSolomonMultiply(result[j].toInt() and 0xFF, root)).toByte()
                    if (j + 1 < result.size)
                        result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = reedSolomonMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonComputeRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1)
                result[result.size - 1] = 0
                for (i in result.indices)
                    result[i] = (result[i].toInt() xor reedSolomonMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        private fun reedSolomonMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private class BuiltQrCode(val modules: Array<BooleanArray>, val mask: Int)

        private fun fromDataCodewords(ver: Int, ecl: Ecc, dataCodewords: ByteArray, msk: Int): QrCode {
            val built = buildModules(ver, ecl, dataCodewords, msk)
            return QrCode(
                version = ver,
                errorCorrectionLevel = ecl,
                mask = built.mask,
                modules = built.modules
            )
        }

        private fun buildModules(ver: Int, ecl: Ecc, dataCodewords: ByteArray, msk: Int): BuiltQrCode {
            if (ver < MIN_VERSION || ver > MAX_VERSION)
                throw IllegalArgumentException("Version value out of range")
            if (msk < -1 || msk > 7)
                throw IllegalArgumentException("Mask value out of range")
            val size = ver * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            val isFunction = Array(size) { BooleanArray(size) }

            drawFunctionPatterns(modules, isFunction, ver, ecl)
            val allCodewords = addEccAndInterleave(ver, ecl, dataCodewords)
            drawCodewords(modules, isFunction, size, allCodewords)

            var chosenMsk = msk
            if (chosenMsk == -1) {
                var minPenalty = Int.MAX_VALUE
                for (i in 0 until 8) {
                    applyMask(modules, isFunction, size, i)
                    drawFormatBits(modules, isFunction, size, ecl, i)
                    val penalty = getPenaltyScore(modules, size)
                    if (penalty < minPenalty) {
                        chosenMsk = i
                        minPenalty = penalty
                    }
                    applyMask(modules, isFunction, size, i)
                }
            }
            applyMask(modules, isFunction, size, chosenMsk)
            drawFormatBits(modules, isFunction, size, ecl, chosenMsk)
            return BuiltQrCode(modules, chosenMsk)
        }

        private fun drawFunctionPatterns(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, ver: Int, ecl: Ecc) {
            val size = modules.size
            for (i in 0 until size) {
                setFunctionModule(modules, isFunction, 6, i, i % 2 == 0)
                setFunctionModule(modules, isFunction, i, 6, i % 2 == 0)
            }

            drawFinderPattern(modules, isFunction, size, 3, 3)
            drawFinderPattern(modules, isFunction, size, size - 4, 3)
            drawFinderPattern(modules, isFunction, size, 3, size - 4)

            val alignPatPos = getAlignmentPatternPositions(ver, size)
            val numAlign = alignPatPos.size
            for (i in 0 until numAlign) {
                for (j in 0 until numAlign) {
                    if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1 || i == numAlign - 1 && j == 0))
                        drawAlignmentPattern(modules, isFunction, alignPatPos[i], alignPatPos[j])
                }
            }

            drawFormatBits(modules, isFunction, size, ecl, 0)
            drawVersion(modules, isFunction, size, ver)
        }

        private fun drawFormatBits(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, size: Int, ecl: Ecc, msk: Int) {
            var data = (ecl.formatBits shl 3) or msk
            var rem = data
            for (i in 0 until 10)
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = (data shl 10 or rem) xor 0x5412

            for (i in 0..5)
                setFunctionModule(modules, isFunction, 8, i, getBitAt(bits, i))
            setFunctionModule(modules, isFunction, 8, 7, getBitAt(bits, 6))
            setFunctionModule(modules, isFunction, 8, 8, getBitAt(bits, 7))
            setFunctionModule(modules, isFunction, 7, 8, getBitAt(bits, 8))
            for (i in 9 until 15)
                setFunctionModule(modules, isFunction, 14 - i, 8, getBitAt(bits, i))

            for (i in 0 until 8)
                setFunctionModule(modules, isFunction, size - 1 - i, 8, getBitAt(bits, i))
            for (i in 8 until 15)
                setFunctionModule(modules, isFunction, 8, size - 15 + i, getBitAt(bits, i))
            setFunctionModule(modules, isFunction, 8, size - 8, true)
        }

        private fun drawVersion(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, size: Int, ver: Int) {
            if (ver < 7) return
            var rem = ver
            for (i in 0 until 12)
                rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = ver shl 12 or rem
            for (i in 0 until 18) {
                val bit = getBitAt(bits, i)
                val a = size - 11 + i % 3
                val b = i / 3
                setFunctionModule(modules, isFunction, a, b, bit)
                setFunctionModule(modules, isFunction, b, a, bit)
            }
        }

        private fun drawFinderPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, size: Int, x: Int, y: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = max(abs(dx), abs(dy))
                    val xx = x + dx
                    val yy = y + dy
                    if (0 <= xx && xx < size && 0 <= yy && yy < size)
                        setFunctionModule(modules, isFunction, xx, yy, dist != 2 && dist != 4)
                }
            }
        }

        private fun drawAlignmentPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, x: Int, y: Int) {
            for (dy in -2..2) {
                for (dx in -2..2)
                    setFunctionModule(modules, isFunction, x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
            }
        }

        private fun setFunctionModule(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, x: Int, y: Int, isDark: Boolean) {
            modules[y][x] = isDark
            isFunction[y][x] = true
        }

        private fun addEccAndInterleave(ver: Int, ecl: Ecc, data: ByteArray): ByteArray {
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver]
            val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver]
            val rawCodewords = getNumRawDataModules(ver) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks

            val blocks = arrayOfNulls<ByteArray>(numBlocks)
            val rsDiv = reedSolomonComputeDivisor(blockEccLen)
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + datLen)
                k += dat.size
                val block = dat.copyOf(shortBlockLen + 1)
                val ecc = reedSolomonComputeRemainder(dat, rsDiv)
                ecc.copyInto(block, block.size - blockEccLen)
                blocks[i] = block
            }

            val result = ByteArray(rawCodewords)
            k = 0
            for (i in 0 until blocks[0]!!.size) {
                for (j in blocks.indices) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[k] = blocks[j]!![i]
                        k++
                    }
                }
            }
            return result
        }

        private fun drawCodewords(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, size: Int, data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6)
                    right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = getBitAt(data[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, size: Int, msk: Int) {
            if (msk < 0 || msk > 7)
                throw IllegalArgumentException("Mask value out of range")
            for (y in 0 until size) {
                for (x in 0 until size) {
                    var invert: Boolean
                    when (msk) {
                        0 -> invert = (x + y) % 2 == 0
                        1 -> invert = y % 2 == 0
                        2 -> invert = x % 3 == 0
                        3 -> invert = (x + y) % 3 == 0
                        4 -> invert = (x / 3 + y / 2) % 2 == 0
                        5 -> invert = x * y % 2 + x * y % 3 == 0
                        6 -> invert = (x * y % 2 + x * y % 3) % 2 == 0
                        else -> invert = ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert && !isFunction[y][x])
                        modules[y][x] = !modules[y][x]
                }
            }
        }

        private fun getPenaltyScore(modules: Array<BooleanArray>, size: Int): Int {
            var result = 0
            for (y in 0 until size) {
                var runColor = false
                var runX = 0
                val runHistory = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runX++
                        if (runX == 5)
                            result += PENALTY_N1
                        else if (runX > 5)
                            result++
                    } else {
                        finderPenaltyAddHistory(runHistory, size, runX)
                        if (!runColor)
                            result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                        runColor = modules[y][x]
                        runX = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runHistory, size, runColor, runX) * PENALTY_N3
            }
            for (x in 0 until size) {
                var runColor = false
                var runY = 0
                val runHistory = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runY++
                        if (runY == 5)
                            result += PENALTY_N1
                        else if (runY > 5)
                            result++
                    } else {
                        finderPenaltyAddHistory(runHistory, size, runY)
                        if (!runColor)
                            result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                        runColor = modules[y][x]
                        runY = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runHistory, size, runColor, runY) * PENALTY_N3
            }

            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1])
                        result += PENALTY_N2
                }
            }

            var dark = 0
            for (row in modules) {
                for (color in row) {
                    if (color)
                        dark++
                }
            }
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * PENALTY_N4
            return result
        }

        private fun finderPenaltyCountPatterns(runHistory: IntArray): Int {
            val n = runHistory[1]
            val core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n
            return (if (core && runHistory[0] >= n * 4 && runHistory[6] >= n) 1 else 0) +
                (if (core && runHistory[6] >= n * 4 && runHistory[0] >= n) 1 else 0)
        }

        private fun finderPenaltyTerminateAndCount(runHistory: IntArray, size: Int, currentRunColor: Boolean, currentRunLength: Int): Int {
            var runLength = currentRunLength
            if (currentRunColor) {
                finderPenaltyAddHistory(runHistory, size, runLength)
                runLength = 0
            }
            runLength += size
            finderPenaltyAddHistory(runHistory, size, runLength)
            return finderPenaltyCountPatterns(runHistory)
        }

        private fun finderPenaltyAddHistory(runHistory: IntArray, size: Int, currentRunLength: Int) {
            var runLength = currentRunLength
            if (runHistory[0] == 0)
                runLength += size
            runHistory.copyInto(runHistory, 1, 0, runHistory.size - 1)
            runHistory[0] = runLength
        }

        private fun getBitAt(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

        private fun getAlignmentPatternPositions(ver: Int, size: Int): IntArray {
            if (ver == 1)
                return IntArray(0)
            val numAlign = ver / 7 + 2
            val step = (ver * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in result.size - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }
    }

    /** The error correction level in a QR Code symbol. */
    enum class Ecc(val formatBits: Int) {
        /** About 7% erroneous codewords can be tolerated. */
        LOW(1),
        /** About 15% erroneous codewords can be tolerated. */
        MEDIUM(0),
        /** About 25% erroneous codewords can be tolerated. */
        QUARTILE(3),
        /** About 30% erroneous codewords can be tolerated. */
        HIGH(2),
    }
}

/**
 * A segment of character/binary data in a QR Code symbol.
 */
class QrSegment private constructor(
    /** The mode indicator of this segment. */
    val mode: Mode,
    /** The length of this segment's unencoded data (characters or bytes). */
    val numChars: Int,
    /** The data bits of this segment. */
    val data: BitBuffer,
) {
    companion object {
        private val ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

        /** Returns a segment representing the specified binary data in byte mode. */
        fun makeBytes(data: ByteArray): QrSegment {
            val bb = BitBuffer()
            for (b in data)
                bb.appendBits(b.toInt() and 0xFF, 8)
            return QrSegment(Mode.BYTE, data.size, bb)
        }

        /** Returns a list of segments representing the specified Unicode text. */
        fun makeSegments(text: String): List<QrSegment> {
            val result = ArrayList<QrSegment>()
            if (text.isEmpty()) return result
            if (isNumeric(text))
                result.add(makeNumeric(text))
            else if (isAlphanumeric(text))
                result.add(makeAlphanumeric(text))
            else
                result.add(makeBytes(text.encodeToByteArray()))
            return result
        }

        private fun makeNumeric(digits: String): QrSegment {
            val bb = BitBuffer()
            var i = 0
            while (i < digits.length) {
                val n = min(digits.length - i, 3)
                bb.appendBits(digits.substring(i, i + n).toInt(), n * 3 + 1)
                i += n
            }
            return QrSegment(Mode.NUMERIC, digits.length, bb)
        }

        private fun makeAlphanumeric(text: String): QrSegment {
            val bb = BitBuffer()
            var i = 0
            while (i <= text.length - 2) {
                var temp = ALPHANUMERIC_CHARSET.indexOf(text[i]) * 45
                temp += ALPHANUMERIC_CHARSET.indexOf(text[i + 1])
                bb.appendBits(temp, 11)
                i += 2
            }
            if (i < text.length)
                bb.appendBits(ALPHANUMERIC_CHARSET.indexOf(text[i]), 6)
            return QrSegment(Mode.ALPHANUMERIC, text.length, bb)
        }

        private fun isNumeric(text: String): Boolean =
            text.isNotEmpty() && text.all { it in '0'..'9' }

        private fun isAlphanumeric(text: String): Boolean =
            text.isNotEmpty() && text.all { it in ALPHANUMERIC_CHARSET }

        /** Calculates the number of bits needed for the given segments at the given version, or -1. */
        fun getTotalBits(segs: List<QrSegment>, version: Int): Int {
            var result: Long = 0
            for (seg in segs) {
                val ccbits = seg.mode.numCharCountBits(version)
                if (seg.numChars >= (1L shl ccbits))
                    return -1
                result += 4L + ccbits + seg.data.bitLength
                if (result > Int.MAX_VALUE)
                    return -1
            }
            return result.toInt()
        }
    }

    /** Describes how a segment's data bits are interpreted. */
    enum class Mode(internal val modeBits: Int, internal val numBitsCharCount: IntArray) {
        NUMERIC(0x1, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(0x2, intArrayOf(9, 11, 13)),
        BYTE(0x4, intArrayOf(8, 16, 16)),
        KANJI(0x8, intArrayOf(8, 10, 12)),
        ECI(0x7, intArrayOf(0, 0, 0));

        /** Returns the bit width of the character count field for a segment in this mode. */
        fun numCharCountBits(ver: Int): Int =
            numBitsCharCount[(ver + 7) / 17]
    }
}

/**
 * An appendable sequence of bits (0s and 1s). Mainly used by [QrSegment].
 */
class BitBuffer {
    private var data = BooleanArray(64)
    var bitLength: Int = 0
        private set

    /** Returns the bit at the specified index, yielding 0 or 1. */
    fun getBit(index: Int): Int {
        if (index < 0 || index >= bitLength)
            throw IndexOutOfBoundsException()
        return if (data[index]) 1 else 0
    }

    /** Appends the low-order [len] bits of [val]. */
    fun appendBits(value: Int, len: Int) {
        if (len < 0 || len > 31 || value ushr len != 0)
            throw IllegalArgumentException("Value out of range")
        if (bitLength + len > data.size)
            data = data.copyOf(max(data.size * 2, bitLength + len))
        for (i in len - 1 downTo 0) {
            data[bitLength] = ((value ushr i) and 1) != 0
            bitLength++
        }
    }

    /** Appends the content of the specified bit buffer to this buffer. */
    fun appendData(bb: BitBuffer) {
        if (bb.bitLength + bitLength > data.size)
            data = data.copyOf(max(data.size * 2, bitLength + bb.bitLength))
        for (i in 0 until bb.bitLength) {
            data[bitLength] = bb.data[i]
            bitLength++
        }
    }
}
