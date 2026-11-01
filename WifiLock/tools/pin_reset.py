#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WifiLock 离线 PIN 重置工具（配合 App「忘记 PIN」功能，全程离线，无服务器）

依赖:
    pip install cryptography

使用步骤:
    1. 在 App「🔐 应用锁」里启用 PIN 时，会显示一串"私钥(Base64)"——
       把它原样保存为 wifilock_reset_private.key（纯文本文件，内容就是那串
       Base64，换行无影响），放到本脚本同目录。该私钥只显示一次，务必保存！
    2. 手机端 App 忘记 PIN 时会显示"申请码"，形如:
           WFL1-1a2b3c4d-0123456789abcdef0123456789abcdef
    3. 运行:
           python pin_reset.py reset WFL1-1a2b3c4d-0123...
       （私钥文件不在同目录时加 --key 路径）
    4. 脚本输出"重置码"，形如:
           WFR1-MEUCIQ...（一串 Base64）
       把它粘贴回 App 的"忘记 PIN"页 → 重置成功后 PIN 被删除。

安全说明:
    - 手机里只存公钥，私钥只在你电脑上 → 拿到手机的人无法自行生成重置码；
    - 申请码 10 分钟有效且一次性（用后作废）；
    - 重置成功会连公钥一起清除，下次启用应用锁需要重新保存新的私钥。
"""

import argparse
import base64
import hashlib
import re
import sys

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    sys.exit("缺少依赖：请先执行  pip install cryptography")


def load_private_key(path: str):
    raw = open(path, "r", encoding="utf-8").read()
    b64 = re.sub(r"\s+", "", raw)
    try:
        der = base64.b64decode(b64, validate=True)
    except Exception as e:
        sys.exit(f"私钥文件格式不对（应为 Base64 文本）: {e}")
    try:
        return serialization.load_der_private_key(der, password=None)
    except Exception as e:
        sys.exit(f"私钥解析失败: {e}")


def key_id(priv) -> str:
    pub = priv.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(pub).hexdigest()[:8]


def do_reset(code: str, key_path: str) -> str:
    m = re.match(r"^WFL1-([0-9a-fA-F]{8})-([0-9a-fA-F]{32})$", code.strip())
    if not m:
        sys.exit('申请码格式不对，应为 WFL1-xxxxxxxx-<64个hex>（在 App“忘记 PIN”页获取）')
    want_id, challenge_hex = m.group(1).lower(), m.group(2)

    priv = load_private_key(key_path)
    if not isinstance(priv, ec.EllipticCurvePrivateKey):
        sys.exit("私钥不是 EC 密钥（App 生成的是 P-256）")
    if priv.curve.name != "secp256r1":
        sys.exit(f"私钥曲线不是 P-256（当前: {priv.curve.name}）")

    kid = key_id(priv)
    if kid != want_id:
        print(f"[警告] 申请码指向的钥匙 {want_id}，与本地私钥 {kid} 不一致——")
        print("       可能设备不对或多台设备混用了钥匙文件。仍将按本地私钥签名，")
        print("       App 端会验签失败。建议确认钥匙文件后重试。\n")

    challenge = bytes.fromhex(challenge_hex)
    sig = priv.sign(challenge, ec.ECDSA(hashes.SHA256()))  # DER 编码
    return "WFR1-" + base64.b64encode(sig).decode("ascii")


def main():
    ap = argparse.ArgumentParser(description="WifiLock 离线 PIN 重置（挑战-应答签名）")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p_reset = sub.add_parser("reset", help="用申请码生成重置码")
    p_reset.add_argument("code", help="App 显示的申请码 WFL1-...")
    p_reset.add_argument("--key", default="wifilock_reset_private.key",
                         help="私钥文件（Base64 of PKCS8），默认 wifilock_reset_private.key")
    args = ap.parse_args()

    if args.cmd == "reset":
        out = do_reset(args.code, args.key)
        print("重置码（整行复制到 App）:")
        print(out)


if __name__ == "__main__":
    main()
