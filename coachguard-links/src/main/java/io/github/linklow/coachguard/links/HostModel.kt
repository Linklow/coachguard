package io.github.linklow.coachguard.links

import java.io.IOException
import java.io.InputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Logistic regression weights exported by `ml/train_phishing_model.py`.
 *
 * Binary layout, little-endian:
 * ```
 * "CGHM"                                     magic
 * int32 x5    format version, feature spec version, bucket count, numeric feature count, weight bits (8 or 16)
 * float64 x4  bias, weight scale, medium threshold, high threshold
 * float64 x3  per numeric feature: mean, standard deviation, weight
 * int8/int16  one quantized weight per hashed bucket; weight = value * scale
 * ```
 */
internal class HostModel(
    val bias: Double,
    val thresholdMedium: Double,
    val thresholdHigh: Double,
    private val scale: Double,
    private val numericMean: DoubleArray,
    private val numericStd: DoubleArray,
    private val numericWeights: DoubleArray,
    private val hashedWeights: ShortArray,
) {

    fun hashedWeight(bucket: Int): Double = hashedWeights[bucket] * scale

    /** Contribution of numeric feature [index] with raw [value] to the logit. */
    fun numericContribution(index: Int, value: Double): Double =
        numericWeights[index] * (value - numericMean[index]) / numericStd[index]

    companion object {
        private const val FORMAT_VERSION = 1
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'H'.code.toByte(), 'M'.code.toByte())

        fun read(input: InputStream): HostModel = parse(input.readBytes())

        fun parse(bytes: ByteArray): HostModel {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            try {
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC)) throw IOException("Not a CoachGuard host model")
                val formatVersion = buffer.int
                val featureVersion = buffer.int
                val buckets = buffer.int
                val numericCount = buffer.int
                val bits = buffer.int
                if (formatVersion != FORMAT_VERSION) throw IOException("Unsupported model format $formatVersion")
                if (featureVersion != HostFeatures.FEATURE_SPEC_VERSION) {
                    throw IOException("Model expects feature spec $featureVersion, library implements ${HostFeatures.FEATURE_SPEC_VERSION}")
                }
                if (buckets != HostFeatures.BUCKETS || numericCount != HostFeatures.NUMERIC_FEATURES.size) {
                    throw IOException("Model shape does not match the feature extractor")
                }
                if (bits != 8 && bits != 16) throw IOException("Unsupported weight size $bits")

                val bias = buffer.double
                val scale = buffer.double
                val thresholdMedium = buffer.double
                val thresholdHigh = buffer.double
                val mean = DoubleArray(numericCount)
                val std = DoubleArray(numericCount)
                val weights = DoubleArray(numericCount)
                for (i in 0 until numericCount) {
                    mean[i] = buffer.double
                    std[i] = buffer.double
                    weights[i] = buffer.double
                }
                val hashed = ShortArray(buckets) { if (bits == 8) buffer.get().toShort() else buffer.short }
                if (buffer.hasRemaining()) throw IOException("Unexpected data after the model")
                return HostModel(bias, thresholdMedium, thresholdHigh, scale, mean, std, weights, hashed)
            } catch (e: BufferUnderflowException) {
                throw IOException("Model file is truncated", e)
            }
        }
    }
}
