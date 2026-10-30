#!/usr/bin/env python3
"""Create a local signing identity once. Never commit its files or passwords."""
import os
from pathlib import Path
import secrets
import subprocess
root = Path(__file__).resolve().parents[1]
config = root / 'android/keystore.properties'
identity = Path.home() / '.local/share/kunnect/release.keystore'
if config.exists():
    print('Existing release signing configuration retained.')
    raise SystemExit(0)
if identity.exists():
    raise SystemExit('A signing key already exists. Restore keystore.properties instead of replacing it.')
identity.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
password = secrets.token_hex(32)
env = dict(os.environ, KUNNECT_KEYSTORE_PASSWORD=password)
keytool = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17')) / 'bin/keytool'
subprocess.run([str(keytool), '-genkeypair', '-keystore', str(identity), '-alias', 'kunnect', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000', '-dname', 'CN=Kunnect, OU=Personal, O=Kunnect', '-storepass:env', 'KUNNECT_KEYSTORE_PASSWORD', '-keypass:env', 'KUNNECT_KEYSTORE_PASSWORD'], env=env, check=True, capture_output=True)
identity.chmod(0o600)
with os.fdopen(os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as f:
    f.write(f'storeFile={identity}\nstorePassword={password}\nkeyAlias=kunnect\nkeyPassword={password}\n')
print('Created a local release signing key and ignored signing configuration. Back up both securely.')
