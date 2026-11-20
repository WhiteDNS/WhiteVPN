#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'native/engines/tor-inputs.json').read_text())
key, output = sys.argv[1], Path(sys.argv[2])
spec = lock[key]
output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists():
    pending = output.with_suffix('.part')
    urllib.request.urlretrieve(spec['url'], pending)
    if hashlib.sha256(pending.read_bytes()).hexdigest() != spec['sha256']:
        raise RuntimeError('Downloaded source checksum mismatch: ' + key)
    pending.replace(output)
if hashlib.sha256(output.read_bytes()).hexdigest() != spec['sha256']:
    raise RuntimeError('Cached source checksum mismatch: ' + key)
print(key + ': checksum verified')
