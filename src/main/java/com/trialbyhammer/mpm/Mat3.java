package com.trialbyhammer.mpm;

/**
 * 极简 3×3 矩阵，行主序。
 *
 * <p>为什么不用 JOML：JOML 是 Minecraft 的依赖，只在游戏运行时存在。
 * MPM 的离线自测不启动游戏，拿不到它。自己写三十行，比折腾依赖稳定。</p>
 */
public final class Mat3 {

    public float m00, m01, m02;
    public float m10, m11, m12;
    public float m20, m21, m22;

    public Mat3() {
        setIdentity();
    }

    public Mat3 setIdentity() {
        m00 = 1; m01 = 0; m02 = 0;
        m10 = 0; m11 = 1; m12 = 0;
        m20 = 0; m21 = 0; m22 = 1;
        return this;
    }

    public Mat3 set(Mat3 o) {
        m00 = o.m00; m01 = o.m01; m02 = o.m02;
        m10 = o.m10; m11 = o.m11; m12 = o.m12;
        m20 = o.m20; m21 = o.m21; m22 = o.m22;
        return this;
    }

    public float det() {
        return m00 * (m11 * m22 - m12 * m21)
             - m01 * (m10 * m22 - m12 * m20)
             + m02 * (m10 * m21 - m11 * m20);
    }

    public Mat3 invert() {
        float d = det();
        if (Math.abs(d) < 1.0E-9F) {
            return setIdentity();
        }
        float inv = 1.0F / d;

        float a00 = m11 * m22 - m12 * m21;
        float a01 = m02 * m21 - m01 * m22;
        float a02 = m01 * m12 - m02 * m11;
        float a10 = m12 * m20 - m10 * m22;
        float a11 = m00 * m22 - m02 * m20;
        float a12 = m02 * m10 - m00 * m12;
        float a20 = m10 * m21 - m11 * m20;
        float a21 = m01 * m20 - m00 * m21;
        float a22 = m00 * m11 - m01 * m10;

        m00 = a00 * inv; m01 = a01 * inv; m02 = a02 * inv;
        m10 = a10 * inv; m11 = a11 * inv; m12 = a12 * inv;
        m20 = a20 * inv; m21 = a21 * inv; m22 = a22 * inv;
        return this;
    }

    public Mat3 transpose() {
        float t;
        t = m01; m01 = m10; m10 = t;
        t = m02; m02 = m20; m20 = t;
        t = m12; m12 = m21; m21 = t;
        return this;
    }

    public Mat3 add(Mat3 o) {
        m00 += o.m00; m01 += o.m01; m02 += o.m02;
        m10 += o.m10; m11 += o.m11; m12 += o.m12;
        m20 += o.m20; m21 += o.m21; m22 += o.m22;
        return this;
    }

    public Mat3 sub(Mat3 o) {
        m00 -= o.m00; m01 -= o.m01; m02 -= o.m02;
        m10 -= o.m10; m11 -= o.m11; m12 -= o.m12;
        m20 -= o.m20; m21 -= o.m21; m22 -= o.m22;
        return this;
    }

    public Mat3 scale(float s) {
        m00 *= s; m01 *= s; m02 *= s;
        m10 *= s; m11 *= s; m12 *= s;
        m20 *= s; m21 *= s; m22 *= s;
        return this;
    }

    /** this = a · b（结果写回 this，允许 this 与 a/b 是同一个对象）。 */
    public Mat3 mul(Mat3 b) {
        float n00 = m00 * b.m00 + m01 * b.m10 + m02 * b.m20;
        float n01 = m00 * b.m01 + m01 * b.m11 + m02 * b.m21;
        float n02 = m00 * b.m02 + m01 * b.m12 + m02 * b.m22;

        float n10 = m10 * b.m00 + m11 * b.m10 + m12 * b.m20;
        float n11 = m10 * b.m01 + m11 * b.m11 + m12 * b.m21;
        float n12 = m10 * b.m02 + m11 * b.m12 + m12 * b.m22;

        float n20 = m20 * b.m00 + m21 * b.m10 + m22 * b.m20;
        float n21 = m20 * b.m01 + m21 * b.m11 + m22 * b.m21;
        float n22 = m20 * b.m02 + m21 * b.m12 + m22 * b.m22;

        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
        m20 = n20; m21 = n21; m22 = n22;
        return this;
    }

    /** 矩阵乘向量（行主序）。 */
    public void mulVec(float vx, float vy, float vz, float[] out) {
        out[0] = m00 * vx + m01 * vy + m02 * vz;
        out[1] = m10 * vx + m11 * vy + m12 * vz;
        out[2] = m20 * vx + m21 * vy + m22 * vz;
    }

    public float frobenius() {
        return (float) Math.sqrt(
                m00 * m00 + m01 * m01 + m02 * m02 +
                m10 * m10 + m11 * m11 + m12 * m12 +
                m20 * m20 + m21 * m21 + m22 * m22);
    }

    public boolean isFinite() {
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m02)
            && Float.isFinite(m10) && Float.isFinite(m11) && Float.isFinite(m12)
            && Float.isFinite(m20) && Float.isFinite(m21) && Float.isFinite(m22);
    }
}
