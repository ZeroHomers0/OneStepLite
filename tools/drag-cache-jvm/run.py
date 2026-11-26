"""Exercise the short-lived drag cache and staging promotion with production code."""
import os, subprocess, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
fixtures=Path(__file__).with_name('fixtures')
paths=[str(path) for path in fixtures.rglob('*.java')]
paths.append(str(src/'DragCacheStore.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.DragCacheSmoke'],check=True)
