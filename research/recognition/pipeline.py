"""Fetch the leading 900 MB of the train-split annotations in resumable chunks, extend the training set, then train.

Usage: python pipeline.py <work_dir> <max_images> <epochs>
"""
import subprocess
import sys
from pathlib import Path

WORK, LIMIT, EPOCHS = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
HERE = Path(__file__).parent
URL = "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv"
HEAD, END, CHUNK = WORK / "oid" / "train-bbox.head.csv", 943718399, 48 * 1024 * 1024

part = HEAD.with_suffix(".chunk")
failures = 0
while HEAD.stat().st_size <= END and failures < 30:
    have = HEAD.stat().st_size
    stop = min(END, have + CHUNK - 1)
    part.unlink(missing_ok=True)
    code = subprocess.run(["curl.exe", "-sS", "-r", f"{have}-{stop}", "--max-time", "300", "--speed-limit", "30000", "--speed-time", "25",
                           "-o", str(part), URL]).returncode
    if code == 0 and part.exists() and part.stat().st_size == stop - have + 1:
        with open(HEAD, "ab") as out:
            out.write(part.read_bytes())
        print("annotations MB:", HEAD.stat().st_size // 2 ** 20, flush=True)
    else:
        failures += 1
        print("chunk failed, retrying", code, flush=True)
part.unlink(missing_ok=True)
print("STEP annotations done", flush=True)
subprocess.run([sys.executable, str(HERE / "extend_train.py"), str(WORK), LIMIT], check=True)
print("STEP dataset done", flush=True)
subprocess.run([sys.executable, str(HERE / "train.py"), str(WORK), EPOCHS], check=True, cwd=str(WORK))
print("STEP training done", flush=True)
