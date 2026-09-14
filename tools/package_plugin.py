#!/usr/bin/env python3
import argparse
import base64
from pathlib import Path

parser = argparse.ArgumentParser(description="Embed a DEX file into an ExteraGram plugin template.")
parser.add_argument("--template", type=Path, required=True)
parser.add_argument("--dex", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()

template = args.template.read_text(encoding="utf-8")
payload = base64.b64encode(args.dex.read_bytes()).decode("ascii")
if template.count("{{DEX_B64}}") != 1:
    raise SystemExit("Template must contain exactly one {{DEX_B64}} marker")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(template.replace("{{DEX_B64}}", payload), encoding="utf-8")
