package com.tapmemory.app;

import android.content.Context;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class ProxyList {
    public static final class Endpoint {
        public final String host;
        public final int port;
        public Endpoint(String h,int p){host=h;port=p;}
        public String toString(){return host+":"+port;}
    }
    public static List<Endpoint> parse(String raw){
        ArrayList<Endpoint> result=new ArrayList<>();
        if(raw==null)return result;
        for(String line:raw.split("\\r?\\n")){
            String trimmed=line.trim();
            if(trimmed.isEmpty()||trimmed.startsWith("#"))continue;
            if(trimmed.startsWith("socks5://"))trimmed=trimmed.substring(9);
            if(trimmed.contains("@")||trimmed.contains("/")||trimmed.contains(" ")){
                throw new IllegalArgumentException("Нужен SOCKS5 без логина: host:port");
            }
            int colon=trimmed.lastIndexOf(':');
            if(colon<=0)throw new IllegalArgumentException("Некорректный адрес: "+trimmed);
            String host=trimmed.substring(0,colon);
            if(!host.matches("[a-zA-Z0-9._-]{1,253}")){
                throw new IllegalArgumentException("Некорректный хост: "+host);
            }
            int port;
            try{port=Integer.parseInt(trimmed.substring(colon+1));}
            catch(Exception ex){throw new IllegalArgumentException("Некорректный порт: "+trimmed);}
            if(port<1||port>65535)throw new IllegalArgumentException("Недопустимый порт");
            result.add(new Endpoint(host,port));
            if(result.size()>40)throw new IllegalArgumentException("Не более 40 прокси");
        }
        return result;
    }
    public static List<Endpoint> fromSettings(Context ctx){
        return parse(MacroStore.pref(ctx).getString("proxy_list",""));
    }
    public static boolean reachable(Endpoint e,int timeoutMs){
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(e.host,e.port),timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write(new byte[]{5,1,0});
            byte[] response=new byte[2];
            int n=0;
            while(n<2){
                int got=socket.getInputStream().read(response,n,2-n);
                if(got<0)return false;
                n+=got;
            }
            return response[0]==5&&response[1]==0;
        }catch(Exception ignored){return false;}
    }
}