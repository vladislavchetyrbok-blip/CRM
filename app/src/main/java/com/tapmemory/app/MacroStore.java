package com.tapmemory.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class MacroStore {
    private static final String PREF="macros";
    static SharedPreferences pref(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static synchronized void init(Context c){
        SharedPreferences p=pref(c);
        if(p.contains("list"))return;
        String id="m1";
        JSONArray list=new JSONArray();JSONObject item=new JSONObject();
        put(item,"id",id);put(item,"name","Мой сценарий");list.put(item);
        p.edit().putString("list",list.toString()).putString("active",id)
            .putString("data_"+id,p.getString("steps","[]")).putInt("sequence",1).commit();
    }
    static synchronized JSONArray macros(Context c){
        init(c);
        try{return new JSONArray(pref(c).getString("list","[]"));}
        catch(Exception e){return new JSONArray();}
    }
    static synchronized String active(Context c){
        init(c);return pref(c).getString("active","m1");
    }
    static synchronized String name(Context c){
        String id=active(c);JSONArray list=macros(c);
        for(int i=0;i<list.length();i++){
            JSONObject m=list.optJSONObject(i);
            if(m!=null&&id.equals(m.optString("id")))return m.optString("name","Сценарий");
        }
        return "Сценарий";
    }
    static synchronized void select(Context c,String id){
        JSONArray list=macros(c);
        for(int i=0;i<list.length();i++){
            JSONObject m=list.optJSONObject(i);
            if(m!=null&&id.equals(m.optString("id"))){
                pref(c).edit().putString("active",id).commit();return;
            }
        }
    }
    static synchronized void create(Context c,String name){
        int next=pref(c).getInt("sequence",1)+1;String id="m"+next;
        JSONArray a=macros(c);
        JSONObject item=new JSONObject();put(item,"id",id);
        put(item,"name",name.trim().isEmpty()?"Сценарий "+next:name.trim());
        a.put(item);
        pref(c).edit().putString("list",a.toString()).putInt("sequence",next)
            .putString("active",id).putString("data_"+id,"[]").commit();
    }
    static synchronized void rename(Context c,String n){
        JSONArray a=macros(c);String id=active(c);
        for(int i=0;i<a.length();i++){
            JSONObject j=a.optJSONObject(i);
            if(j!=null&&id.equals(j.optString("id")))put(j,"name",n.trim());
        }
        pref(c).edit().putString("list",a.toString()).commit();
    }
    static synchronized void deleteActive(Context c){
        JSONArray a=macros(c);
        if(a.length()<=1){save(c,new JSONArray());return;}
        JSONArray b=new JSONArray();String old=active(c);
        for(int i=0;i<a.length();i++){
            JSONObject m=a.optJSONObject(i);
            if(m!=null&&!old.equals(m.optString("id")))b.put(m);
        }
        String first=b.optJSONObject(0).optString("id");
        pref(c).edit().remove("data_"+old).remove("target_"+old)
            .putString("list",b.toString()).putString("active",first).commit();
    }
    static synchronized JSONArray steps(Context c){
        try{return new JSONArray(pref(c).getString("data_"+active(c),"[]"));}
        catch(Exception e){return new JSONArray();}
    }
    static synchronized void save(Context c,JSONArray a){
        pref(c).edit().putString("data_"+active(c),a.toString()).commit();
    }
    static synchronized void add(Context c,JSONObject step){
        JSONArray a=steps(c);a.put(step);save(c,a);
    }
    static synchronized void replace(Context c,int i,JSONObject j){
        JSONArray a=steps(c);
        if(i<0||i>=a.length())return;
        try{a.put(i,j);save(c,a);}catch(Exception ignored){}
    }
    static synchronized void remove(Context c,int index){
        JSONArray a=steps(c),b=new JSONArray();
        for(int i=0;i<a.length();i++)if(i!=index)b.put(a.optJSONObject(i));
        save(c,b);
    }
    static synchronized void move(Context c,int from,int to){
        JSONArray a=steps(c);
        if(from<0||from>=a.length()||to<0||to>=a.length())return;
        JSONObject x=a.optJSONObject(from),y=a.optJSONObject(to);
        try{a.put(from,y);a.put(to,x);}catch(Exception ignored){}
        save(c,a);
    }
    static synchronized void setTarget(Context c,String pkg){
        pref(c).edit().putString("target_"+active(c),pkg).commit();
    }
    static synchronized String target(Context c){
        return pref(c).getString("target_"+active(c),"");
    }
    static JSONObject step(String type,long delay){
        JSONObject j=new JSONObject();put(j,"type",type);put(j,"delay",Math.max(0,Math.min(3600000,delay)));
        return j;
    }
    static void put(JSONObject j,String k,Object v){
        try{j.put(k,v);}catch(Exception ignored){}
    }
}