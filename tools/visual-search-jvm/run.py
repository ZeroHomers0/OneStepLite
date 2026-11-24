"""Embedded image search keeps the actual image in a fixed bounded multipart form; browser handoff was removed."""
from pathlib import Path
import os,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
body=r'''package io.github.zerohomers0.onesteplite;
public class VisualSearchRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static void invalid(String data,int w,int h){boolean rejected=false;try{VisualSearchForm.create(data,w,h);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid payload rejected before HTML");}
public static void main(String[] args){
String image="YWJjZA==",form=VisualSearchForm.create(image,640,480);
check(form.contains("name='imageBin' value='"+image+"'"),"selected image is carried into the page rather than an empty search URL");
check(form.contains("method='POST'")&&form.contains("enctype='multipart/form-data'"),"image uses web upload protocol");
check(form.contains("https://www.bing.com/images/search?"),"fixed HTTPS destination");
check(form.contains("thw=640&thh=480&expw=640&exph=480&dlen=8"),"actual dimensions and encoded data length retained");
check(form.contains("name='cbir' value='sbi'")&&form.contains("document.forms[0].submit()"),"inline view automatically submits image");
check(!form.contains("type='file'")&&!form.contains("file://")&&!form.contains("content://"),"no local file picker or URI disclosure");
invalid(null,1,1);invalid("",1,1);invalid("' onload='oops",1,1);invalid(image,0,1);invalid(image,1,0);invalid(image,801,1);invalid(image,1,801);invalid("a".repeat(2*1024*1024+1),1,1);
check(VisualSearchForm.create(image,800,800).contains("thw=800"),"maximum bounded image accepted");
System.out.println("Production embedded visual search checks="+checks+" passed (replaces removed browser route checks)");}}
'''
path=out/'VisualSearchRegression.java';path.write_text(body,encoding='utf-8');jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(src/'VisualSearchForm.java'),str(path)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.VisualSearchRegression'],check=True)
