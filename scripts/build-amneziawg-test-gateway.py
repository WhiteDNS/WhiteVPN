#!/usr/bin/env python3
"""Prepare the disposable local AWG integration fixture; never an app dependency."""
import json
import os
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/amneziawg/test-gateway"
OUTPUT = ROOT / "build/engines/amnezia-test-gateway"
MODULE = "github.com/amnezia-vpn/amneziawg-go/v3"

def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, GOTOOLCHAIN="go1.26.8",
               GOPATH=str(ROOT / "build/engines/go-path"),
               GOCACHE=str(ROOT / "build/engines/go-cache"))
    go = shutil.which("go") or "C:/Program Files/Go/bin/go.exe"
    for name in ("main.go", "go.mod", "go.sum"):
        shutil.copyfile(SOURCE / name, OUTPUT / name)
    subprocess.run([go, "mod", "download"], cwd=OUTPUT, env=env, check=True)
    data = json.loads(subprocess.check_output(
        [go, "list", "-m", "-json", MODULE], cwd=OUTPUT, env=env, text=True))
    upstream = Path(data["Dir"]) / "tun/netstack/tun.go"
    contents = upstream.read_text()
    # Forwarded client source addresses belong to the same overlay as the gateway.
    # HandleLocal=true causes gVisor to reject these packets as invalid local sources.
    contents, count = re.subn(r"HandleLocal:\s*true", "HandleLocal: false", contents)
    assert count == 1
    contents += "\nfunc (net *Net) Stack() *stack.Stack { return net.stack }\n"
    directory = OUTPUT / "netstack"
    directory.mkdir(exist_ok=True)
    (directory / "tun.go").write_text(contents)
    shutil.copyfile(Path(data["Dir"]) / "LICENSE", directory / "LICENSE-Upstream")
    executable = OUTPUT / ("gateway.exe" if os.name == "nt" else "gateway")
    subprocess.run([go, "build", "-mod=readonly", "-trimpath", "-o", str(executable), "."],
                   cwd=OUTPUT, env=env, check=True)
    print("Built local fixture:", executable)

if __name__ == "__main__":
    main()
