package com.example.miniseckill.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Deterministic command double executing the real Lua source. NOT a Redis integration test. */
public final class CoordinationRedis {
    private final Map<String,Object> data = new HashMap<>();
    private final Map<String,Long> expiry = new HashMap<>();
    private long now = 1_000_000;
    private static final class Hash extends LinkedHashMap<String,String> { }
    private static final class Zset extends LinkedHashMap<String,Double> { }
    public synchronized void advance(long millis) { now+=millis; expire(); }
    private void expire() {
        var it=expiry.entrySet().iterator();
        while(it.hasNext()) { var e=it.next(); if(e.getValue()<=now) { data.remove(e.getKey()); it.remove(); } }
    }
    public synchronized void set(String key, String value) { data.put(key,value); expiry.remove(key); }
    public synchronized String get(String key) { expire(); return (String)data.get(key); }
    public synchronized boolean lease(String key,String owner,long millis) {
        expire(); if(data.containsKey(key))return false; data.put(key,owner);expiry.put(key,now+millis);return true;
    }
    public synchronized int hlen(String key) { expire(); return data.containsKey(key)?((Hash)data.get(key)).size():0; }
    public synchronized String hget(String key,String field) { expire(); return data.containsKey(key)?((Hash)data.get(key)).get(field):null; }
    public synchronized void hash(String key,String field,String value) { ((Hash)data.computeIfAbsent(key,k->new Hash())).put(field,value); }
    public synchronized void delete(String key) {data.remove(key);expiry.remove(key);}
    public synchronized LuaValue eval(String script,List<String> keys,String... argv) {
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("lua/"+script+".lua")) {
            if(in==null)throw new IllegalArgumentException(script);
            return source(new String(in.readAllBytes(),StandardCharsets.UTF_8),keys,argv);
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
    }
    public synchronized LuaValue source(String source,List<String> keys,String... argv) {
        expire(); Globals g=JsePlatform.standardGlobals(); LuaTable api=new LuaTable();
        api.set("call",new VarArgFunction() { @Override public Varargs invoke(Varargs args) {return command(args);} });
        g.set("redis",api);g.set("KEYS",array(keys));g.set("ARGV",array(Arrays.asList(argv)));
        return g.load(source,"production-script").call();
    }
    private static LuaTable array(List<String> values) {LuaTable a=new LuaTable();for(int i=0;i<values.size();i++)a.set(i+1,values.get(i));return a;}
    private LuaValue command(Varargs a) {
        expire();String op=a.arg(1).checkjstring().toUpperCase(Locale.ROOT);
        if(op.equals("TIME"))return array(List.of(Long.toString(now/1000),Long.toString((now%1000)*1000)));
        String k=a.arg(2).checkjstring();Object value=data.get(k);
        switch(op) {
            case "TYPE": {LuaTable type=new LuaTable();type.set("ok",value==null?"none":value instanceof Hash?"hash":value instanceof Zset?"zset":"string");return type;}
            case "GET": return value==null?LuaValue.FALSE:LuaValue.valueOf((String)value);
            case "SET": {
                String v=a.arg(3).tojstring();long ttl=0;boolean nx=false;
                for(int i=4;i<=a.narg();i++){String option=a.arg(i).tojstring();if(option.equalsIgnoreCase("NX"))nx=true;else if(option.equalsIgnoreCase("PX"))ttl=a.arg(++i).tolong();else throw new IllegalArgumentException(option);}
                if(nx&&value!=null)return LuaValue.FALSE;
                set(k,v);if(ttl>0)expiry.put(k,now+ttl);return LuaValue.valueOf("OK");
            }
            case "EXISTS": return LuaValue.valueOf(value==null?0:1);
            case "DEL": delete(k);return LuaValue.valueOf(value==null?0:1);
            case "INCR": case "DECR": {long v=Long.parseLong(value==null?"0":(String)value)+(op.equals("INCR")?1:-1);data.put(k,Long.toString(v));return LuaValue.valueOf(v);}
            case "HLEN": return LuaValue.valueOf(value==null?0:((Hash)value).size());
            case "HEXISTS": return LuaValue.valueOf(value!=null&&((Hash)value).containsKey(a.arg(3).tojstring())?1:0);
            case "HGET": {String v=value==null?null:((Hash)value).get(a.arg(3).tojstring());return v==null?LuaValue.FALSE:LuaValue.valueOf(v);}
            case "HSET": {Hash h=(Hash)data.computeIfAbsent(k,x->new Hash());return LuaValue.valueOf(h.put(a.arg(3).tojstring(),a.arg(4).tojstring())==null?1:0);}
            case "HDEL": {if(value==null)return LuaValue.ZERO;Hash h=(Hash)value;boolean hit=h.remove(a.arg(3).tojstring())!=null;if(h.isEmpty())delete(k);return LuaValue.valueOf(hit?1:0);}
            case "ZADD": {Zset z=(Zset)data.computeIfAbsent(k,x->new Zset());return LuaValue.valueOf(z.put(a.arg(4).tojstring(),a.arg(3).todouble())==null?1:0);}
            case "ZREM": {if(value==null)return LuaValue.ZERO;Zset z=(Zset)value;boolean hit=z.remove(a.arg(3).tojstring())!=null;if(z.isEmpty())delete(k);return LuaValue.valueOf(hit?1:0);}
            case "ZRANGEBYSCORE": {
                if(value==null)return new LuaTable();double max=a.arg(4).todouble();int limit=a.arg(7).toint();
                List<String> ids=((Zset)value).entrySet().stream().filter(e->e.getValue()<=max)
                    .sorted(Map.Entry.<String,Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit).map(Map.Entry::getKey).toList();return array(ids);
            }
            default:throw new IllegalArgumentException("unsupported test command "+op);
        }
    }
}
