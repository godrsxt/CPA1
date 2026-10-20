package com.venice.carromaim.core

import kotlin.math.abs

/** 3x3 projective transform (row-major, m[8] == 1). Maps board <-> screen. */
class Homography(val m: DoubleArray) {

    fun mapX(x: Double, y: Double): Double =
        (m[0] * x + m[1] * y + m[2]) / (m[6] * x + m[7] * y + m[8])

    fun mapY(x: Double, y: Double): Double =
        (m[3] * x + m[4] * y + m[5]) / (m[6] * x + m[7] * y + m[8])

    fun inverse(): Homography {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val ca = e * i - f * h
        val cb = -(d * i - f * g)
        val cc = d * h - e * g
        val det = a * ca + b * cb + c * cc
        val r = DoubleArray(9)
        r[0] = ca / det
        r[1] = -(b * i - c * h) / det
        r[2] = (b * f - c * e) / det
        r[3] = cb / det
        r[4] = (a * i - c * g) / det
        r[5] = -(a * f - c * d) / det
        r[6] = cc / det
        r[7] = -(a * h - b * g) / det
        r[8] = (a * e - b * d) / det
        val n = r[8]
        for (k in 0 until 9) r[k] /= n
        return Homography(r)
    }

    companion object {
        /** src/dst: 4 points each as x0,y0,x1,y1,... Returns null if degenerate. */
        fun quadToQuad(src: DoubleArray, dst: DoubleArray): Homography? {
            val a = Array(8) { DoubleArray(9) }
            for (k in 0 until 4) {
                val x = src[2 * k]; val y = src[2 * k + 1]
                val u = dst[2 * k]; val v = dst[2 * k + 1]
                val r1 = a[2 * k]; val r2 = a[2 * k + 1]
                r1[0] = x; r1[1] = y; r1[2] = 1.0; r1[6] = -u * x; r1[7] = -u * y; r1[8] = u
                r2[3] = x; r2[4] = y; r2[5] = 1.0; r2[6] = -v * x; r2[7] = -v * y; r2[8] = v
            }
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                val d = a[col][col]
                for (c in col until 9) a[col][c] /= d
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col]
                    if (f == 0.0) continue
                    for (c in col until 9) a[r][c] -= f * a[col][c]
                }
            }
            val m = DoubleArray(9)
            for (k in 0 until 8) m[k] = a[k][8]
            m[8] = 1.0
            return Homography(m)
        }

        /** Board unit square (TL,TR,BR,BL) -> screen corners given as 8 floats. */
        fun boardToScreen(corners: FloatArray): Homography? {
            val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
            val dst = DoubleArray(8) { corners[it].toDouble() }
            return quadToQuad(src, dst)
        }
    }
}
