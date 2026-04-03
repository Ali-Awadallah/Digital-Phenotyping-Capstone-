#!/usr/bin/env python3
"""
Rotate backend secrets in .env.

Usage:
  python rotate_keys.py
  python rotate_keys.py --admin capstone --ingest <value> --data-key <base64-32-byte>
"""

from __future__ import annotations

import argparse
import base64
import os
import secrets
from pathlib import Path


def generate_api_key() -> str:
    return secrets.token_urlsafe(48)


def generate_data_key_b64() -> str:
    return base64.b64encode(os.urandom(32)).decode("ascii")


def read_env_lines(path: Path) -> list[str]:
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def write_env_lines(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def upsert_env_values(lines: list[str], updates: dict[str, str]) -> list[str]:
    remaining = dict(updates)
    out: list[str] = []

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            out.append(line)
            continue
        key, _ = line.split("=", 1)
        key = key.strip()
        if key in remaining:
            out.append(f"{key}={remaining.pop(key)}")
        else:
            out.append(line)

    for key, value in remaining.items():
        out.append(f"{key}={value}")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Rotate API/encryption keys in backend .env")
    parser.add_argument("--env-file", default=".env", help="Path to env file (default: .env)")
    parser.add_argument("--admin", default=None, help="Admin API key value (default: random)")
    parser.add_argument("--ingest", default=None, help="Ingest API key value (default: random)")
    parser.add_argument("--data-key", dest="data_key", default=None, help="DATA_ENCRYPTION_KEY_B64 value (default: random AES-256 key)")
    parser.add_argument("--skip-admin", action="store_true", help="Do not rotate API_KEY_ADMIN")
    parser.add_argument("--skip-ingest", action="store_true", help="Do not rotate API_KEY_INGEST")
    parser.add_argument("--skip-data-key", action="store_true", help="Do not rotate DATA_ENCRYPTION_KEY_B64")
    args = parser.parse_args()

    env_path = Path(args.env_file).resolve()
    lines = read_env_lines(env_path)

    updates: dict[str, str] = {}
    if not args.skip_admin:
        updates["API_KEY_ADMIN"] = args.admin if args.admin is not None else generate_api_key()
    if not args.skip_ingest:
        updates["API_KEY_INGEST"] = args.ingest if args.ingest is not None else generate_api_key()
    if not args.skip_data_key:
        updates["DATA_ENCRYPTION_KEY_B64"] = args.data_key if args.data_key is not None else generate_data_key_b64()

    if not updates:
        print("Nothing to rotate. Use without --skip-* flags.")
        return 0

    new_lines = upsert_env_values(lines, updates)
    write_env_lines(env_path, new_lines)

    print(f"Updated secrets in: {env_path}")
    print("Restart backend and signature engine containers to apply new keys.")
    print("Updated keys:")
    for key, value in updates.items():
        print(f"  {key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

