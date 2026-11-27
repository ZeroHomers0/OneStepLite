"""Enforce architecture invariants and monotonic refactor baselines."""
from pathlib import Path
import re, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
pkg=Path(root)/"app/src/main/java/io/github/zerohomers0/onesteplite"
sources=package_dir(root)
here=Path(__file__).resolve().parent
checks=0
def check(value,why):
 global checks
 checks+=1
 if not value: raise AssertionError(why)
def rel(path): return path.relative_to(root).as_posix()
# Host entry/shell files may not grow new nested types.
for name in ('WorkspaceHost.java','HostEntry.java'):
 text=(sources/name).read_text(encoding='utf-8')
 nested=re.search(r'(?m)^\s+(?:public |private |protected |static |final |abstract |sealed |non-sealed )*(?:class|record|enum|interface)\s+',text)
 check(nested is None,name+' must keep only its outer type')
# Policy remains pure JVM logic.
for path in sorted((pkg/'policy').glob('*.java')):
 text=path.read_text(encoding='utf-8')
 check('import android.' not in text,'policy must not import Android: '+rel(path))
# Legacy oversized methods are explicit and may only disappear.
allowed_methods=set(line.strip() for line in (here/'long-methods.baseline').read_text().splitlines() if line.strip())
for name in ('WorkspaceHost.java','HostEntry.java'):
 text=(sources/name).read_text(encoding='utf-8')
 pattern=r'(?m)^\s{4}(?:public |private |protected )?(?:static )?(?:final )?(?:synchronized )?[\w<>\[\], ?.]+\s+(\w+)\s*\([^;]*?\)\s*(?:throws [^{]+)?\{'
 for m in re.finditer(pattern,text):
  start=m.start();brace=text.index('{',start);depth=1;i=brace+1
  while depth and i<len(text):
   if text[i]=='{':depth+=1
   elif text[i]=='}':depth-=1
   i+=1
  lines=len(text[start:i].splitlines())
  if lines>100:
   key=name+':'+m.group(1)
   check(key in allowed_methods,'method exceeds 100 lines without baseline: '+key)
# Legacy reflection outside platform may only be removed from this list.
allowed_reflection=set(line.strip() for line in (here/'xposed-outside-platform.baseline').read_text().splitlines() if line.strip())
for path in sorted(pkg.rglob('*.java')):
 if 'platform' in path.parts: continue
 text=path.read_text(encoding='utf-8')
 if 'XposedHelpers' in text or 'XposedBridge' in text:
  check(rel(path) in allowed_reflection,'reflection outside platform has no baseline: '+rel(path))
print('Architecture guard checks=%d passed' % checks)
