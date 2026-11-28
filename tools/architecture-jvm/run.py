"""Enforce architecture invariants and monotonic refactor baselines."""
from pathlib import Path
import re, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
pkg=Path(root)/"app/src/main/java/io/github/zerohomers0/onesteplite"
sources=package_dir(root)
here=Path(__file__).resolve().parent
update='--update' in sys.argv
checks=0

def check(value,why):
    global checks
    checks+=1
    if not value: raise AssertionError(why)

def rel(path): return path.relative_to(root).as_posix()
def category(path):
    parts=path.relative_to(pkg).parts[:-1]
    return '/'.join(parts) if parts else '.'


def code_only(text):
    text=re.sub(r'/\*.*?\*/','',text,flags=re.S)
    text=re.sub(r'//[^\n]*','',text)
    text=re.sub(r'"(?:\\.|[^"\\])*"','""',text)
    text=re.sub(r"'(?:\\.|[^'\\])*'","''",text)
    return text

def under(value,prefix):
    return value==prefix or value.startswith(prefix+'/')
def baseline(name):
    path=here/name
    return set(line.strip() for line in path.read_text(encoding='utf-8').splitlines() if line.strip())

def save_baseline(name,values):
    (here/name).write_text(''.join(value+'\n' for value in sorted(values)),encoding='utf-8')

def interfaces(text):
    for match in re.finditer(r'(?m)^\s*(?:public\s+|private\s+|protected\s+)?interface\s+(\w+)\s*\{',text):
        end=match.end();depth=1
        while depth and end<len(text):
            depth+=(text[end]=='{')-(text[end]=='}');end+=1
        body=text[match.end():end-1]
        methods=[line.strip() for line in body.splitlines()
                 if '(' in line and line.strip().endswith(';')]
        yield match.group(1),methods

# Host entry/shell files may not grow new nested types.
for name in ('WorkspaceHost.java','HostEntry.java'):
    text=(sources/name).read_text(encoding='utf-8')
    nested=re.search(r'(?m)^\s+(?:public |private |protected |static |final |abstract |sealed |non-sealed )*(?:class|record|enum|interface)\s+',text)
    check(nested is None,name+' must keep only its outer type')

# Pure policy and cross-process contracts stay free of framework dependencies.
for path in sorted((pkg/'policy').glob('*.java')):
    text=path.read_text(encoding='utf-8')
    check('import android.' not in text,'policy must not import Android: '+rel(path))
for path in sorted((pkg/'contract').glob('*.java')):
    text=path.read_text(encoding='utf-8')
    check('import android.' not in text,'contract must not import Android: '+rel(path))
    check('import de.robv.android.xposed.' not in text,'contract must not import Xposed: '+rel(path))

# Feature/UI layers may not contain direct Xposed compatibility code.
for area in ('apps','workspace'):
    for path in sorted((pkg/area).rglob('*.java')):
        text=path.read_text(encoding='utf-8')
        check('XposedHelpers' not in text and 'XposedBridge' not in text
              and 'de.robv.android.xposed.' not in text,
              area+' must not own Xposed hooks: '+rel(path))

for name in ('SystemUiEntry.java','SystemServerHooks.java'):
    text=(sources/name).read_text(encoding='utf-8')
    check(text.count('HookGuard.run(')+text.count('HookGuard.install(')>=2,
          name+' must isolate feature hooks with HookGuard')
# Platform folders are integration boundaries, not generic controller buckets.
for path in sorted((pkg/'systemui/platform').glob('*.java')):
    check(path.name.endswith('Hooks.java') or path.name.startswith('Native'),
          'systemui/platform accepts only hook/native boundary classes: '+rel(path))

# Build one physical-directory dependency graph. The checked-in baseline is exact:
# a removed dependency must be deleted so intentional boundary improvements stay visible.
type_owners={}
for path in pkg.rglob('*.java'):
    text=path.read_text(encoding='utf-8')
    for name in re.findall(r'(?m)^\s*(?:public\s+|private\s+|protected\s+|static\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(?:class|interface|enum|record)\s+(\w+)',text):
        type_owners.setdefault(name,[]).append(path)
edges=set()
for path in pkg.rglob('*.java'):
    raw=path.read_text(encoding='utf-8')
    source=category(path);text=code_only(raw)
    external={name for name in re.findall(r'(?m)^import\s+(?:static\s+)?[\w.]+\.(\w+)\s*;',raw)}
    for name,owners in type_owners.items():
        if len(owners)!=1 or name in external: continue
        target=category(owners[0])
        if source==target: continue
        if re.search(r'(?<!\.)\b'+re.escape(name)+r'\b',text):
            edges.add(source+' -> '+target)
allowed_contract_targets={'contract','core','policy'}
for edge in edges:
    source,target=[part.strip() for part in edge.split('->',1)]
    if source=='contract':
        check(target in allowed_contract_targets,'contract may not depend on '+target)
    check(not (under(source,'systemserver') and under(target,'systemui')),
          'systemserver may not depend on systemui: '+edge)
    check(not (under(source,'systemui') and under(target,'launcher')),
          'systemui may not depend on launcher: '+edge)
    check(not (source.startswith('systemui/') and under(target,'launcher')),
          'systemui may not depend on launcher: '+edge)
    check(not (source=='framework' and under(target,'apps')),
          'framework may not own app controllers: '+edge)
    check(not (source=='framework' and under(target,'systemui')),
          'framework may not own SystemUI controllers: '+edge)
    check(not (source=='framework' and under(target,'systemserver')),
          'framework may not own system_server controllers: '+edge)
    if source=='policy':
        check(not under(target,'systemserver') and not under(target,'systemui')
              and not under(target,'framework') and not under(target,'apps'),
              'policy may not depend on platform/UI layers: '+edge)
dependency_file='directory-dependencies.baseline'
if update: save_baseline(dependency_file,edges)
else:
    expected=baseline(dependency_file)
    missing=expected-edges
    unexpected=edges-expected
    check(not missing,'dependency baseline has removed edges; run with --update: '+', '.join(sorted(missing)))
    check(not unexpected,'new cross-directory dependency needs review: '+', '.join(sorted(unexpected)))

# Legacy oversized methods are explicit and may only disappear.
allowed_methods=baseline('long-methods.baseline')
pattern=r'(?m)^\s{4}(?:public |private |protected )?(?:static )?(?:final )?(?:synchronized )?[\w<>\[\], ?.]+\s+(\w+)\s*\([^;]*?\)\s*(?:throws [^{]+)?\{'
for name in ('WorkspaceHost.java','HostEntry.java'):
    text=(sources/name).read_text(encoding='utf-8')
    for m in re.finditer(pattern,text):
        start=m.start();brace=text.index('{',start);depth=1;i=brace+1
        while depth and i<len(text):
            depth+=(text[i]=='{')-(text[i]=='}');i+=1
        lines=len(text[start:i].splitlines())
        if lines>100:
            key=name+':'+m.group(1)
            check(key in allowed_methods,'method exceeds 100 lines without baseline: '+key)

# Moved methods must fit the existing per-method limit without adding legacy exceptions.
oversized=[]
for name in ('SystemServerHooks.java','TaskRotationController.java','ZoomTransactions.java',
             'SystemServerZoomBridge.java','SystemUiEntry.java','WorkspaceNativeHooks.java',
             'WorkspaceMainSideController.java','WorkspaceMemoryController.java',
             'WorkspaceLiveHandoffController.java','WorkspacePromotionController.java',
             'WorkspaceTopPresentationController.java','WorkspaceTopAdmissionController.java',
             'WorkspaceTaskTransferController.java','WorkspaceRetainedSurfaceHooks.java',
             'WorkspaceWindowPresentation.java','WorkspaceWindowLayout.java',
             'WorkspaceKeyguardController.java','WorkspaceMainLaunchController.java',
             'WorkspaceMainTaskActions.java','WorkspaceTransitionWindow.java',
             'WorkspaceLiveGeometry.java','WorkspaceContentDragController.java',
             'WorkspaceSlotLifecycle.java','WorkspaceZoomSession.java',
             'WorkspaceSystemEvents.java','WorkspaceSideInteraction.java'):
    text=(sources/name).read_text(encoding='utf-8')
    for match in re.finditer(pattern.replace(r'^\s{4}',r'^ {4}'),text):
        end=match.end();depth=1
        while depth and end<len(text):
            depth+=(text[end]=='{')-(text[end]=='}');end+=1
        if len(text[match.start():end].splitlines())>100:
            oversized.append(name+':'+match.group(1))
check(not oversized,'moved method exceeds 100 lines: '+', '.join(oversized))

# New hook files use HookGuard; legacy direct installs are an exact shrinking baseline.
raw_hook=re.compile(r'XposedBridge\.(?:hookAllMethods|hookMethod|hookAllConstructors)|XposedHelpers\.findAndHook(?:Method|Constructor)')
raw_hook_files={rel(path) for path in pkg.rglob('*.java')
                if path.name!='HookGuard.java' and raw_hook.search(path.read_text(encoding='utf-8'))}
if update: save_baseline('raw-hooks.baseline',raw_hook_files)
else:
    expected=baseline('raw-hooks.baseline')
    check(not (raw_hook_files-expected),'new raw hook installation must use HookGuard: '+', '.join(sorted(raw_hook_files-expected)))
    check(not (expected-raw_hook_files),'raw-hook baseline is stale; run with --update: '+', '.join(sorted(expected-raw_hook_files)))

# Wide host interfaces are frozen; new interfaces stay narrow (at most eight methods).
wide={'display/swap/LiveSwapSession.java:Backend','display/swap/LiveSwapSession.java:Scene'}
current_wide=set()
for path in pkg.rglob('*.java'):
    for name,methods in interfaces(path.read_text(encoding='utf-8')):
        if len(methods)>8: current_wide.add(rel(path)+':'+name)
if update: save_baseline('wide-interfaces.baseline',current_wide)
else:
    expected=baseline('wide-interfaces.baseline')
    check(not (current_wide-expected),'wide interface needs decomposition: '+', '.join(sorted(current_wide-expected)))
    check(not (expected-current_wide),'wide-interface baseline is stale; run with --update: '+', '.join(sorted(expected-current_wide)))

# Cross-process call directions that still exist are exact exceptions and may only disappear.
systemui_to_systemserver={edge for edge in edges
    if under(edge.split(' -> ',1)[0],'systemui') and under(edge.split(' -> ',1)[1],'systemserver')}
systemserver_to_apps={edge for edge in edges
    if under(edge.split(' -> ',1)[0],'systemserver') and under(edge.split(' -> ',1)[1],'apps')}
check(not systemui_to_systemserver,
      'SystemUI must cross contract/framework instead of calling system_server: '+', '.join(sorted(systemui_to_systemserver)))
check(not systemserver_to_apps,
      'system_server content hooks must use contract/framework, not apps: '+', '.join(sorted(systemserver_to_apps)))
# Controllers migrate to WorkspacePorts monotonically; new controllers may not capture the whole Host.
host_controllers=set()
for path in (pkg/'systemui').rglob('*Controller.java'):
    text=path.read_text(encoding='utf-8')
    if re.search(r'\(WorkspaceHost host\b|final WorkspaceHost host\b',text):
        host_controllers.add(rel(path))
if update: save_baseline('workspace-host-controllers.baseline',host_controllers)
else:
    expected=baseline('workspace-host-controllers.baseline')
    check(not (host_controllers-expected),
          'new controller must depend on WorkspacePorts, not WorkspaceHost: '+', '.join(sorted(host_controllers-expected)))
    check(not (expected-host_controllers),
          'WorkspaceHost controller baseline is stale; run with --update: '+', '.join(sorted(expected-host_controllers)))
# Pure JVM unit tests must not pull Android or Xposed symbols.
for path in sorted((root/'tests/unit').glob('*.java')):
    text=path.read_text(encoding='utf-8')
    check('android.' not in text and 'de.robv.android.xposed.' not in text,
          'tests/unit must stay framework-free: '+rel(path))

# Legacy reflection outside platform may only be removed from this list.
allowed_reflection=baseline('xposed-outside-platform.baseline')
current_reflection=set()
for path in sorted(pkg.rglob('*.java')):
    if 'platform' in path.parts: continue
    text=path.read_text(encoding='utf-8')
    if 'XposedHelpers' in text or 'XposedBridge' in text: current_reflection.add(rel(path))
if update: save_baseline('xposed-outside-platform.baseline',current_reflection)
else:
    check(not (current_reflection-allowed_reflection),'reflection outside platform has no baseline: '+', '.join(sorted(current_reflection-allowed_reflection)))
    check(not (allowed_reflection-current_reflection),'reflection baseline is stale; run with --update: '+', '.join(sorted(allowed_reflection-current_reflection)))

print('Architecture guard checks=%d passed' % checks)