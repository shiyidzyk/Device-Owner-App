package com.example.wifilock

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * 应用锁（PIN 码）+ 离线重置（挑战-应答）。
 *
 * 设计（纯离线，无任何服务器）：
 * - PIN 以「迭代 SHA-256 KDF」存储（盐 16B 随机 + 5 万次迭代），不存明文；
 * - 会话窗：onStop（离开应用/划后台/进程死）记录时刻，10 分钟内回来免重验，
 *   超时后任何入口都要求重验（lastPause 持久化，进程被杀也能正确判断）；
 * - 重置 = EC P-256 挑战-应答签名：
 *     启用 PIN 时生成密钥对，私钥（PKCS8 Base64）只显示一次，要求用户保存到电脑；
 *     本机只存公钥（X509 Base64）。泄露面 = 只有公钥，无法离线伪造重置码。
 *   忘记 PIN 时：App 生成一次性挑战（10 分钟有效，格式 WFL1-<keyid8>-<32hex>），
 *   PC 端 tools/pin_reset.py 用私钥对挑战做 ECDSA-SHA256 签名输出 WFR1-<b64>，
 *   App 用存储公钥验签，通过即删除 PIN（回到未启用状态）。
 * - 防爆破：连续错 5 次进入指数退避锁定（30s 起，最多封顶）。
 *
 * 注意：修改 PIN 复用同一密钥对（公钥不变 → 已保存的私钥继续可用）；
 * 「重置成功」与「手动关闭」都会连公钥一起清除（下次启用重新生成、需重新保存私钥）。
 */
object PinManager {

    private const val PREFS = "pin_lock"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val ITERATIONS = 50_000
    private const val CHALLENGE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_WRONG = 5
    private const val LOCKOUT_BASE_MS = 30_000L
    private const val LOCKOUT_CAP_MS = 30 * 60 * 1000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // KDF：迭代 SHA-256（不依赖具体 Provider，API 24+ 全兼容）
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // 状态与会话窗
    // ------------------------------------------------------------------
    fun isEnabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)

    /** 10 分钟免重验窗口：以最近一次离开应用（onStop）的时刻为起点 */
    fun isSessionValid(ctx: Context): Boolean {
        val lastPause = p(ctx).getLong("lastPause", 0L)
        if (lastPause <= 0L) return false
        val elapsed = System.currentTimeMillis() - lastPause
        return elapsed in 0 until WINDOW_MS
    }

    /** 离开应用时调用（onStop） */
    fun markBackgrounded(ctx: Context) {
        p(ctx).edit().putLong("lastPause", System.currentTimeMillis()).apply()
    }

    /** 验证通过 / 功能关闭时调用 */
    fun refreshSession(ctx: Context) {
        p(ctx).edit().putLong("lastPause", System.currentTimeMillis()).apply()
    }

    /** 距离解锁还剩多少毫秒（0 = 可尝试） */
    fun lockoutRemaining(ctx: Context): Long {
        val until = p(ctx).getLong("lockoutUntil", 0L)
        val rest = until - System.currentTimeMillis()
        return if (rest > 0) rest else 0L
    }

    // ------------------------------------------------------------------
    // 启用 / 验证 / 关闭
    // ------------------------------------------------------------------

    /**
     * 设置 PIN。返回一次性私钥（PKCS8 Base64，每 64 字符换行便于誊抄）；
     * 若本机已存有重置公钥（改密场景），密钥对保持不变。
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

        var privateKeyOnce = ""
        if (p(ctx).getString("pubkey", null) == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            sp.putString("pubkey", pub)
            // 只在启用时返回一次，随后内存中丢弃（本机不留私钥）
            privateKeyOnce = chunk64(priv)
        }
        sp.apply()
        return privateKeyOnce
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

    /** 删除 PIN（重置成功 / 手动关闭共用）：全部状态清空，回到未启用 */
    fun clearPin(ctx: Context) {
        p(ctx).edit().clear()
            .putLong("lastPause", System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------------
    // 挑战-应答重置
    // ------------------------------------------------------------------

    fun hasResetKey(ctx: Context): Boolean =
        p(ctx).getString("pubkey", null) != null

    /** 公钥指纹前 8 个 hex（挑战码携带，供 PC 端选对钥匙文件） */
    fun keyId(ctx: Context): String = try {
        val pub = Base64.decode(p(ctx).getString("pubkey", "") ?: "", Base64.NO_WRAP)
        sha256(pub).joinToString("") { "%02x".format(it) }.substring(0, 8)
    } catch (_: Throwable) {
        "00000000"
    }

    /**
     * 生成一次性申请码：WFL1-<keyid8hex>-<challenge32hex>
     * 每次调用都会作废旧挑战；10 分钟内有效。
     */
    fun newChallenge(ctx: Context): String {
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }
        p(ctx).edit()
            .putString("challenge", hex)
            .putLong("challengeAt", System.currentTimeMillis())
            .apply()
        return "WFL1-${keyId(ctx)}-$hex"
    }

    /** 验证 PC 端重置码（WFR1-<base64(DER 签名)>）；成功即删除 PIN 并返回 true */
    fun verifyResetCode(ctx: Context, input: String): Boolean {
        val sp = p(ctx)
        val pubB64 = sp.getString("pubkey", null) ?: return false
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
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
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

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun chunk64(s: String): String =
        s.chunked(64).joinToString("\n")
}
