from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
files = {
'android/os/IBinder.java': '''package android.os;
public interface IBinder {
 int FIRST_CALL_TRANSACTION=1;
 boolean isBinderAlive();
 void linkToDeath(DeathRecipient d,int flags) throws RemoteException;
 boolean unlinkToDeath(DeathRecipient d,int flags);
 interface DeathRecipient { void binderDied(); }
}''',
'android/os/RemoteException.java': 'package android.os; public class RemoteException extends Exception {}',
'io/github/zerohomers0/onesteplite/ZoomHostLeaseRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.os.*;
public class ZoomHostLeaseRegression {
 static int checks; static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final class Token implements IBinder {
  boolean alive=true,linked,unlinked;DeathRecipient death;
  public boolean isBinderAlive(){return alive;}
  public void linkToDeath(DeathRecipient d,int f)throws RemoteException{if(!alive)throw new RemoteException();death=d;linked=true;}
  public boolean unlinkToDeath(DeathRecipient d,int f){unlinked=true;death=null;return true;}
  void die(){alive=false;DeathRecipient d=death;death=null;if(d!=null)d.binderDied();}
 }
 public static void main(String[] args)throws Exception{
  int[] lost={0};ZoomHostLease lease=new ZoomHostLease(()->lost[0]++);
  Token first=new Token(),second=new Token();
  check(lease.accept(first)&&lease.alive()&&first.linked,"first host acquires lease");
  check(lease.accept(first)&&first.linked,"same host is idempotent");
  check(lease.accept(second)&&first.unlinked&&second.linked&&lease.alive(),"replacement unlinks old host");
  second.die();check(!lease.alive()&&lost[0]==1,"binder death expires lease exactly once");
  check(!lease.accept(second),"dead token is rejected");
  System.out.println("ZoomHostLease checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths.append(str(root/'app/src/main/java/io/github/zerohomers0/onesteplite/systemserver/zoom/ZoomHostLease.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ZoomHostLeaseRegression'],check=True)
