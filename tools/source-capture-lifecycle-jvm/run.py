"""Replay production capture validity and cleanup without platform screenshot privileges."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (src / 'SourceCaptureController.java').read_text(encoding='utf-8-sig')

def member(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

methods = member('    private boolean valid(') + '\n' + member('    @Override public void close()')
test = '''package io.github.zerohomers0.onesteplite;
import java.util.function.*;
public final class CaptureLifecycleRegression {
static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
static class Bitmap {int recycles;void recycle(){recycles++;}}
static class Dialog {int dismisses;void dismiss(){dismisses++;}}
static class Lease {int closes;void close(){closes++;}}
static class Anchor {boolean attached=true;boolean isAttachedToWindow(){return attached;}}
static class CurrentUser {static int user;static int id(){return user;}}
static class Capture implements AutoCloseable {
final Object pixels=new Object();Anchor anchor=new Anchor();BooleanSupplier ready=()->true;Supplier<Object> bounds=()->this.requested;
Object requested=new Object(),crop=new Object(),initialCrop=new Object();boolean closed,busy=true,dragging=true,consumed;int generation=42,discards,owner;
Bitmap snapshot=new Bitmap(),result=new Bitmap();Dialog dialog=new Dialog();Lease island=new Lease();String storedId="pending";
Object textResult;Lease recognition;
void discard(String id){discards++;}
__METHODS__
}
public static void main(String[] args){
Capture capture=new Capture();check(capture.valid(42),"same active scene may show crop");
check(!capture.valid(41),"retired request cannot publish captured frame");
CurrentUser.user=10;check(!capture.valid(42),"changed user cannot publish previous user's frame");CurrentUser.user=0;
capture.anchor.attached=false;check(!capture.valid(42),"detached host cannot publish app frame");capture.anchor.attached=true;
capture.ready=()->false;check(!capture.valid(42),"locked or inactive host cannot publish app frame");capture.ready=()->true;
capture.bounds=Object::new;check(!capture.valid(42),"changed geometry invalidates capture");
Bitmap original=capture.snapshot,selected=capture.result;Dialog dialog=capture.dialog;Lease lease=capture.island;
capture.close();check(capture.closed&&!capture.busy&&!capture.dragging&&capture.generation==43,"close invalidates async workers and interaction state");
check(original.recycles==1&&selected.recycles==1,"close frees full frame and cropped result");
check(dialog.dismisses==1&&lease.closes==1,"close dismisses crop window and returns island lease");
check(capture.discards==1&&capture.storedId==null,"unconsumed capture is deleted from provider");
check(capture.snapshot==null&&capture.result==null&&capture.crop==null&&capture.dialog==null,"close retains no content views or pixels");
capture.close();check(original.recycles==1&&selected.recycles==1&&capture.discards==1,"duplicate close never double-frees pixels or records");
check(!capture.valid(43),"closed capture remains invalid even with current generation");
capture=new Capture();capture.consumed=true;capture.close();check(capture.discards==0,"accepted drag keeps temporary URI alive for receiver");
capture=new Capture();capture.storedId=null;capture.result=null;capture.close();check(capture.discards==0,"cancel during capture read has no fabricated record to delete");
System.out.println("Production source capture lifecycle checks="+checks+" passed");
}}
'''.replace('__METHODS__', methods)
path = out / 'CaptureLifecycleRegression.java'
path.write_text(test, encoding='utf-8')
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(path)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.CaptureLifecycleRegression'], check=True)
