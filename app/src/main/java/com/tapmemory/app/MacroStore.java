package com.tapmemory.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class MacroStore {
    private static final String PREF = "macros";
    static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    static synchronized JSONArray steps(Context c) {
        try { return new JSONArray(pref(c).getString("steps","[]")); }
        catch(Exception e) { return new JSONArray(); }
    }
    static synchronized void save(Context c, JSONArray a) {
        pref(c).edit().putString("steps", a.toString()).apply();
    }
    static synchronized void add(Context c, JSONObject step) {
        JSONArray a = steps(c); a.put(step); save(c,a);
    }
    static synchronized void remove(Context c, int i) {
        JSONArray a = steps(c), b = new JSONArray();
        for(int k=0;k<a.length();k++) if(k!=i) b.put(a.optJSONObject(k));
        save(c,b);
    }
    static synchronized void move(Context c,int from,int to) {
        JSONArray a=steps(c);
        if(from<0 || from>=a.length() || to<0 || to>=a.length()) return;
        JSONArray b=new JSONArray();
        for(int i=0;i<a.length();i++) {
            int src=i==from?to:i==to?from:i;
            b.put(a.optJSONObject(src));
        }
        save(c,b);
    }
    static synchronized void replaceLastText(Context c, JSONObject step) {
        JSONArray a = steps(c);
        if(a.length()>0 && "text".equals(a.optJSONObject(a.length()-1).optString("type"))) {
            JSONArray b=new JSONArray();
            for(int i=0;i<a.length()-1;i++) b.put(a.optJSONObject(i));
            b.put(step); save(c,b);
        } else add(c,step);
    }
    static JSONObject step(String type,long delay) {
        JSONObject j=new JSONObject();
        try { j.put("type",type); j.put("delay",Math.max(100,Math.min(60000,delay))); }
        catch(Exception ignored) {}
        return j;
    }
    static void put(JSONObject j,String key,Object value) {
        try { j.put(key,value); } catch(Exception ignored) {}
    }
}