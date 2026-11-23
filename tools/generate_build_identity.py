"""Generate the Java build identity from the sole version source."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--version', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
version = json.loads(args.version.read_text(encoding='utf-8-sig'))
target = args.output / 'io/github/zerohomers0/onesteplite/BuildIdentity.java'
target.parent.mkdir(parents=True, exist_ok=True)
marker = json.dumps(version['runtimeMarker'] + ' ===', ensure_ascii=True)
target.write_text('package io.github.zerohomers0.onesteplite;\n'
                  'final class BuildIdentity {\n'
                  f'    static final String RUNTIME_MARKER = {marker};\n'
                  '    private BuildIdentity() {}\n}\n', encoding='utf-8', newline='\n')
