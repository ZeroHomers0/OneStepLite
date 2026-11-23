"""Replay the production OCR wire reader with malformed and mixed-language parcels."""
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
files = {
'android/os/BadParcelableException.java': 'package android.os; public class BadParcelableException extends RuntimeException {public BadParcelableException(String s){super(s);}}',
'android/os/Parcel.java': '''package android.os;
public class Parcel {final Object[] values;int at;
public Parcel(Object...values){this.values=values;}
public int readInt(){return (Integer)values[at++];}
public float readFloat(){return (Float)values[at++];}
public String readString(){return (String)values[at++];}
public int dataAvail(){return (values.length-at)*4;}}''',
'io/github/zerohomers0/onesteplite/OcrRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.os.Parcel;import android.os.BadParcelableException;
public class OcrRegression {
static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
static void rejects(Object...data){boolean rejected=false;try{OcrResultReader.read(new Parcel(data));}catch(BadParcelableException expected){rejected=true;}check(rejected);}
public static void main(String[] args){
check(OcrResultReader.read(new Parcel(0))==null);
check("你好 Hello 😀".equals(OcrResultReader.read(new Parcel(1,0,"你好 Hello 😀",0))));
check("text".equals(OcrResultReader.read(new Parcel(1,1,1,1,1,null,"line",7,1,1,null,"x",.9f,.9f,"paragraph","text",0))));
check("x".equals(OcrResultReader.read(new Parcel(1,0,"x",1,1,null,"entity",1,0,0,1))));
check("x".equals(OcrResultReader.read(new Parcel(1,-1,"x",-1))));
rejects(2);rejects(1,-2);rejects(1,Integer.MAX_VALUE);rejects(1,0,"x",-2);
rejects(1,1,2,0,0,0);rejects(1,1,1,1,1,"unexpected.Class",0,0,0,0,0,0);
rejects(1,0,"x".repeat(100001),0);
System.out.println("Production OCR parcel checks="+checks+" passed (engine device validation pending)");
}}'''
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths,
                str(src / 'OcrResultReader.java'), str(src / 'ContentSharePolicy.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.OcrRegression'], check=True)
