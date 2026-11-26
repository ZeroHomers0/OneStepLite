"""Replay clipboard result rendering without losing history on a current-clip failure."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentPanel.java').read_text(encoding='utf-8-sig')
start=source.index('    private void applyClipboard(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
apply_method=source[start:end]
files={
'io/github/zerohomers0/onesteplite/ContentClipboardRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;
class ContentEntry{final String key;ContentEntry(String key){this.key=key;}}
class Shell{final List<String> headings=new ArrayList<>();String status;
 void heading(String value,int width){headings.add(value);}void status(String value){status=value;}}
public class ContentClipboardRegression{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Replay{
  final Shell shell=new Shell();final Map<String,ContentEntry> entries=new LinkedHashMap<>();
  int contentWidth=320,restoredToken=-1,restoredUser=-1;
  boolean valid(int token,int user){return token==7&&user==3;}
  void addEntry(ContentEntry entry){entries.put(entry.key,entry);}void addEntries(java.util.Collection<ContentEntry> rows){for(ContentEntry row:rows)addEntry(row);}
  void restorePosition(int token,int user){restoredToken=token;restoredUser=user;}
__METHOD__
 }
 public static void main(String[] args){
  Replay history=new Replay();history.applyClipboard(List.of(),List.of(new ContentEntry("history:1")),
          "当前剪贴板暂不支持此内容",7,3);
  check(history.shell.headings.equals(List.of("当前剪贴板暂不支持此内容","历史")),"history survives current clipboard failure");
  check(history.entries.containsKey("history:1")&&history.shell.status==null,"history remains visible without replacing the page");
  check(history.restoredToken==7&&history.restoredUser==3,"clipboard result keeps the request identity");

  Replay both=new Replay();both.applyClipboard(List.of(new ContentEntry("current:1")),
          List.of(new ContentEntry("history:1")),null,7,3);
  check(both.shell.headings.equals(List.of("当前剪贴板","历史")),"current and history keep separate headings");
  check(both.entries.size()==2&&both.shell.status==null,"current and history render together");

  Replay empty=new Replay();empty.applyClipboard(List.of(),List.of(),"当前剪贴板暂不支持此内容",7,3);
  check(empty.shell.status!=null&&empty.shell.status.contains("暂无历史记录"),"empty history uses an inline empty state");
  check(empty.entries.isEmpty()&&empty.shell.headings.equals(List.of("当前剪贴板暂不支持此内容")),"failed current clip does not fabricate content");
  System.out.println("Production clipboard rendering checks="+checks+" passed");
 }
}'''.replace('__METHOD__',apply_method)
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ContentClipboardRegression'],check=True)
