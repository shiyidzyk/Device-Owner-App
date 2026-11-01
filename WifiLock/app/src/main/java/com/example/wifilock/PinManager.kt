package com.example.wifilock

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 应用锁（PIN 码）+ 离线重置（挑战-应答）。
 *
 * 设计（纯离线，无任何服务器）：
 * - PIN 以「迭代 SHA-256 KDF」存储（盐 16B 随机 + 5 万次迭代），不存明文；
 * - 会话状态（v1.4 严格模式）：只要离开应用（onStop，旋转等配置变化除外）立即失效；
 *   解锁标志仅存内存，进程被杀同样失效；
 * - 重置 = EC P-256 挑战-应答签名；
 * - **重置私钥 v2（v1.5 起）：由设备稳定标识确定性派生**——
 *   源 = Settings.Secure.ANDROID_ID（应用签名级隔离：重启/重装/清数据均不变，
 *   仅恢复出厂会变——而恢复出厂本身会清掉 PIN，届时重新启用生成新钥，语义自洽）；
 *   d = HMAC-SHA256(应用常量盐, ANDROID_ID || "WifiLock-reset-key-v1" || counter)，
 *   保证 1 ≤ d < n。**禁用/重新启用应用锁、重装、清数据后私钥不再变更**；
 *   公钥点 Q = d·G 由本文件内置的纯 Kotlin P-256 点乘计算（含 NIST 2G 自检向量），
 *   不再持久化——因此密钥只与设备绑定。
 *
 *   为什么不用 SN/IMEI：Android 10+ 起系统禁止第三方应用读取（需
 *   READ_PRIVILEGED_PHONE_STATE 系统权限），本项目无 root 无 owner，取不到；
 *   ANDROID_ID 是"应用可见且设备级稳定"的最长标识，效果等同（详见移交文档 §18）。
 *
 *   导出格式（v2）：`WFLKEY2-<64 位 hex 私钥标量>`；PC 端 pin_reset.py 已同时兼容
 *   v2（hex 标量）与 v1（Base64 PKCS8，仅 v1.4 老用户升级后首次重启用时会更换一次）。
 *
 * - 防爆破：连续错 5 次进入指数退避锁定（30s 起，最多封顶）。
 */
object PinManager {

    private const val PREFS = "pin_lock"
    private const val ITERATIONS = 50_000
    private const val CHALLENGE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_WRONG = 5
    private const val LOCKOUT_BASE_MS = 30_000L
    private const val LOCKOUT_CAP_MS = 30 * 60 * 1000L

    /** 派生算法盐（公开常量：安全性来自 ANDROID_ID 的应用级隔离，见类注释/移交文档 §18） */
    private val HMAC_KEY = "WifiLock/ResetKeyDerivation/2026".toByteArray(Charsets.UTF_8)
    private const val RESET_INFO = "WifiLock-reset-key-v1"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================================================================
    // 会话状态（严格模式）
    // ==================================================================
    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    /** 验证通过 / 启用应用锁成功后调用 */
    fun markUnlocked() {
        unlocked = true
    }

    /** 离开应用时调用（onStop 且非配置变化） */
    fun markLeft() {
        unlocked = false
    }

    // ==================================================================
    // KDF：迭代 SHA-256（不依赖具体 Provider，API 24+ 全兼容）
    // ==================================================================
    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (b in parts) md.update(b)
        return md.digest()
    }

    private fun kdf(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        var h = sha256(salt, pin.toByteArray(Charsets.UTF_8))
        for (i in 0 until iterations) {
            h = sha256(h, salt)
        }
        return h
    }

    // ==================================================================
    // P-256 曲线数学（纯 Kotlin，供确定性派生公钥点；自检见 SELF_TEST_*）
    // ==================================================================
    private object P256 {
        val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val A = P.subtract(BigInteger.valueOf(3))
        val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
        val GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
        val GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
        val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        /** NIST P-256 基点 2 倍点（已用 python cryptography 核对），运行时自检用 */
        val TWO_G_X = BigInteger("7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", 16)
        val TWO_G_Y = BigInteger("07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1", 16)
    }

    /** 仿射坐标点；x==null 表示无穷远点 */
    private class Pt(val x: BigInteger?, val y: BigInteger?)

    private fun ptAdd(p1: Pt, p2: Pt): Pt {
        if (p1.x == null || p1.y == null) return p2
        if (p2.x == null || p2.y == null) return p1
        if (p1.x == p2.x) {
            return if (p1.y.add(p2.y).mod(P256.P).signum() == 0) {
                Pt(null, null) // P + (-P) = 无穷远
            } else {
                ptDouble(p1)
            }
        }
        val lambda = p2.y.subtract(p1.y)
            .multiply(p2.x.subtract(p1.x).modInverse(P256.P))
            .mod(P256.P)
        val x3 = lambda.multiply(lambda).subtract(p1.x).subtract(p2.x).mod(P256.P)
        val y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P256.P)
        return Pt(x3, y3)
    }

    private fun ptDouble(pt: Pt): Pt {
        if (pt.x == null || pt.y == null || pt.y.signum() == 0) return Pt(null, null)
        val lambda = pt.x.multiply(pt.x).multiply(BigInteger.valueOf(3)).add(P256.A)
            .multiply(pt.y.shiftLeft(1).modInverse(P256.P))
            .mod(P256.P)
        val x3 = lambda.multiply(lambda).subtract(pt.x.shiftLeft(1)).mod(P256.P)
        val y3 = lambda.multiply(pt.x.subtract(x3)).subtract(pt.y).mod(P256.P)
        return Pt(x3, y3)
    }

    private fun ptMul(k: BigInteger, pt: Pt): Pt {
        var result = Pt(null, null)
        var addend = pt
        var kk = k
        while (kk.signum() > 0) {
            if (kk.testBit(0)) result = ptAdd(result, addend)
            addend = ptDouble(addend)
            kk = kk.shiftRight(1)
        }
        return result
    }

    // ==================================================================
    // 确定性重置密钥（设备绑定）
    // ==================================================================
    private var cachedKey: Pair<BigInteger, Pt>? = null // (d, Q)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun androidIdBytes(ctx: Context): ByteArray =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "").toByteArray(Charsets.UTF_8)

    private fun deriveScalar(ctx: Context): BigInteger {
        val id = androidIdBytes(ctx)
        var counter = 0
        while (true) {
            val data = id + RESET_INFO.toByteArray(Charsets.UTF_8) +
                byteArrayOf(
                    (counter shr 24).toByte(), (counter shr 16).toByte(),
                    (counter shr 8).toByte(), counter.toByte()
                )
            val d = BigInteger(1, hmacSha256(HMAC_KEY, data))
            if (d.signum() > 0 && d < P256.N) return d
            counter++
        }
    }

    /** (私钥标量 d, 公钥点 Q)；每进程只算一次（点乘 + 自检 ~几十毫秒） */
    @Synchronized
    private fun resetKey(ctx: Context): Pair<BigInteger, Pt> {
        cachedKey?.let { return it }
        val d = deriveScalar(ctx)
        val g = Pt(P256.GX, P256.GY)
        // 自检：实现正确性由 NIST 2G 向量背书；失败直接抛错而不是给出坏钥
        val self = ptMul(BigInteger.TWO, g)
        if (self.x != P256.TWO_G_X || self.y != P256.TWO_G_Y) {
            throw IllegalStateException("重置密钥模块自检失败（P-256 点乘与标准向量不符）")
        }
        val q = ptMul(d, g)
        if (q.x == null) throw IllegalStateException("派生出的公钥点为无穷远（不应发生）")
        val pair = d to q
        cachedKey = pair
        return pair
    }

    /** X.509 SubjectPublicKeyInfo DER（P-256 未压缩点，固定模板 + 点坐标） */
    private fun spkiDer(q: Pt): ByteArray {
        val head = hexToBytes("3059301306072a8648ce3d020106082a8648ce3d030107034200")
        return head + byteArrayOf(0x04) + left32(q.x!!) + left32(q.y!!)
    }

    private fun left32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    // ==================================================================
    // 状态与门禁
    // ==================================================================
    fun isEnabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)

    /** 距离解锁还剩多少毫秒（0 = 可尝试） */
    fun lockoutRemaining(ctx: Context): Long {
        val until = p(ctx).getLong("lockoutUntil", 0L)
        val rest = until - System.currentTimeMillis()
        return if (rest > 0) rest else 0L
    }

    // ==================================================================
    // 启用 / 验证 / 关闭
    // ==================================================================

    /**
     * 设置 PIN。返回一次性私钥（v2 格式：WFLKEY2-<64hex>，设备绑定——
     * 之后重装/清数据/反复开关应用锁都不会变，直到恢复出厂）。
     * v1.4 升级：旧版随机公钥记录在此清除，私钥文件需按新格式重新保存一次。
     */
    fun setPin(ctx: Context, pin: String): String {
        val sp = p(ctx).edit()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = kdf(pin, salt, ITERATIONS)
        sp.putBoolean("enabled", true)
        sp.putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        sp.putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
        sp.putInt("wrongCount", 0)
        sp.remove("lockoutUntil")
        sp.remove("pubkey") // v1.5 迁移：不再使用随机公钥记录
        sp.apply()
        return exportPrivateOnce(ctx)
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val sp = p(ctx)
        if (lockoutRemaining(ctx) > 0) return false
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val stored = Base64.decode(sp.getString("hash", "") ?: "", Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(kdf(pin, salt, ITERATIONS), stored)
        if (ok) {
            sp.edit().putInt("wrongCount", 0).remove("lockoutUntil").apply()
            return true
        }
        val wc = sp.getInt("wrongCount", 0) + 1
        val e = sp.edit().putInt("wrongCount", wc)
        if (wc >= MAX_WRONG) {
            val backoff = (LOCKOUT_BASE_MS shl (wc - MAX_WRONG).coerceAtMost(6))
                .coerceAtMost(LOCKOUT_CAP_MS)
            e.putLong("lockoutUntil", System.currentTimeMillis() + backoff)
        }
        e.apply()
        return false
    }

    /** 删除 PIN（重置成功 / 手动关闭共用）。密钥是设备派生的，无需（也不会）变更。 */
    fun clearPin(ctx: Context) {
        unlocked = false
        p(ctx).edit().clear().apply()
    }

    // ==================================================================
    // 挑战-应答重置
    // ==================================================================

    /** v1.5 起密钥由设备派生，恒可用 */
    fun hasResetKey(ctx: Context): Boolean = true

    /** 公钥指纹前 8 个 hex（挑战码携带，供 PC 端选对钥匙文件） */
    fun keyId(ctx: Context): String = try {
        bytesToHex(sha256(spkiDer(resetKey(ctx).second))).substring(0, 8)
    } catch (_: Throwable) {
        "00000000"
    }

    /**
     * 生成一次性申请码：WFL1-<keyid8hex>-<challenge32hex>
     * 每次调用都会作废旧挑战；10 分钟内有效。
     */
    fun newChallenge(ctx: Context): String {
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hex = bytesToHex(raw)
        p(ctx).edit()
            .putString("challenge", hex)
            .putLong("challengeAt", System.currentTimeMillis())
            .apply()
        return "WFL1-${keyId(ctx)}-$hex"
    }

    /** 验证 PC 端重置码（WFR1-<base64(DER 签名)>）；成功即删除 PIN 并返回 true */
    fun verifyResetCode(ctx: Context, input: String): Boolean {
        val sp = p(ctx)
        val challengeHex = sp.getString("challenge", null) ?: return false
        val at = sp.getLong("challengeAt", 0L)
        if (System.currentTimeMillis() - at > CHALLENGE_TTL_MS) return false

        val body = input.trim()
            .removePrefix("WFR1-").removePrefix("wfr1-")
            .replace("\n", "").replace("\r", "").replace(" ", "")
        val sig = try {
            Base64.decode(body, Base64.NO_WRAP)
        } catch (_: Throwable) {
            return false
        }
        return try {
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(spkiDer(resetKey(ctx).second)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            // 与 PC 端脚本一致：对挑战的原始 16 字节签名（不是 hex 字符串的 ASCII）
            verifier.update(hexToBytes(challengeHex))
            val ok = verifier.verify(sig)
            if (ok) clearPin(ctx)
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** 一次性导出：WFLKEY2-<64 位 hex 私钥标量>（PC 端 v2 格式） */
    private fun exportPrivateOnce(ctx: Context): String =
        "WFLKEY2-" + resetKey(ctx).first.toString(16).padStart(64, '0')

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
