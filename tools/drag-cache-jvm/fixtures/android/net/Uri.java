package android.net;
import java.util.*;
public final class Uri {
  final String value; private Uri(String v){value=v;}
  public static Uri parse(String v){return new Uri(v);}
  public String getScheme(){int i=value.indexOf(':');return i<0?null:value.substring(0,i);}
  public String getAuthority(){int i=value.indexOf("://");if(i<0)return null;int e=value.indexOf('/',i+3);return value.substring(i+3,e<0?value.length():e);}
  public List<String> getPathSegments(){String rest=value.substring(value.indexOf("://")+3);int s=rest.indexOf('/');if(s<0)return List.of();String p=rest.substring(s+1);return p.isEmpty()?List.of():Arrays.asList(p.split("/"));}
  public String getLastPathSegment(){List<String> p=getPathSegments();return p.isEmpty()?null:p.get(p.size()-1);}
  public Builder buildUpon(){return new Builder(value);} public String toString(){return value;}
  public static final class Builder{String value;Builder(String v){value=v;}public Builder appendPath(String v){return new Builder(value+(value.endsWith("/")?"":"/")+v);}public Uri build(){return new Uri(value);}}
}
