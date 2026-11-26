package com.kilon.avstorture

/**
 * Deterministic SplitMix64 generator so the same (scene, seed) produces identical delays
 * and coordinates across runs and platforms.
 */
class SceneRng(seed: Int) {
    private var state: Long = seed.toLong() xor -0x61c8864680b583ebL

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound.toLong()).toInt()
    }

    fun nextRange(minInclusive: Int, maxInclusive: Int): Int {
        if (maxInclusive <= minInclusive) return minInclusive
        return minInclusive + nextInt(maxInclusive - minInclusive + 1)
    }

    fun nextFloat(): Float = ((nextLong() ushr 40).toInt() / 16777216f)
}
