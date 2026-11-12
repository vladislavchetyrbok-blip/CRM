package com.tapmemory.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class ProxyProbe {
    static String exitIp(ProxyList.Endpoint ep) {
        return exitIp(ep,"","");
    }
    static String exitIp(ProxyList.Endpoint ep,String user,String password) {
        String remote="api.ipify.org";
        int timeout=7000;
        try(Socket raw=new Socket()){
            raw.connect(new InetSocketAddress(ep.host,ep.port),timeout);
            raw.setSoTimeout(timeout);
            OutputStream out=raw.getOutputStream();
            boolean auth=!user.isEmpty()&&!password.isEmpty();
            out.write(new byte[]{5,1,(byte)(auth?2:0)});out.flush();
            byte[] reply=new byte[2];readAll(raw,reply);
            if(reply[0]!=5||reply[1]!=(auth?2:0))return null;
            if(auth){
                byte[] name=user.getBytes(StandardCharsets.UTF_8);
                byte[] secret=password.getBytes(StandardCharsets.UTF_8);
                if(name.length<1||name.length>255||secret.length<1||secret.length>255)return null;
                byte[] message=new byte[3+name.length+secret.length];
                message[0]=1;message[1]=(byte)name.length;
                System.arraycopy(name,0,message,2,name.length);
                message[2+name.length]=(byte)secret.length;
                System.arraycopy(secret,0,message,3+name.length,secret.length);
                out.write(message);out.flush();
                byte[] authReply=new byte[2];readAll(raw,authReply);
                if(authReply[0]!=1||authReply[1]!=0)return null;
            }
            byte[] hostname=remote.getBytes(StandardCharsets.US_ASCII);
            byte[] connect=new byte[7+hostname.length];
            connect[0]=5;connect[1]=1;connect[2]=0;connect[3]=3;connect[4]=(byte)hostname.length;
            System.arraycopy(hostname,0,connect,5,hostname.length);
            connect[5+hostname.length]=1;connect[6+hostname.length]=(byte)187; // 443
            out.write(connect);out.flush();
            byte[] ans=new byte[4];readAll(raw,ans);
            if(ans[0]!=5||ans[1]!=0)return null;
            int addrlen;
            switch(ans[3]){
                case 1:addrlen=4;break;
                case 4:addrlen=16;break;
                case 3:
                    byte[] len=new byte[1];readAll(raw,len);
                    addrlen=len[0]&0xff;break;
                default:return null;
            }
            if(addrlen>0)readAll(raw,new byte[addrlen+2]);
            else readAll(raw,new byte[2]);
            try(SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
                .createSocket(raw,remote,443,true)){
                ssl.setSoTimeout(timeout);
                SSLParameters params=ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                ssl.getOutputStream().write(("GET / HTTP/1.1\r\nHost: "+remote+
                    "\r\nConnection: close\r\nAccept: text/plain\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                ssl.getOutputStream().flush();
                BufferedReader in=new BufferedReader(new InputStreamReader(
                    ssl.getInputStream(),StandardCharsets.US_ASCII));
                String first=in.readLine();
                if(first==null||!first.contains(" 200 "))return null;
                String line;
                while((line=in.readLine())!=null&&!line.isEmpty()){}
                String ip=in.readLine();
                if(ip==null)return null;
                ip=ip.trim();
                if(ip.length()>64||!ip.matches("[0-9a-fA-F:.]{3,64}"))return null;
                return ip;
            }
        }catch(Exception ignored){return null;}
    }
    private static void readAll(Socket socket,byte[] bytes)throws Exception{
        int read=0;
        while(read<bytes.length){
            int n=socket.getInputStream().read(bytes,read,bytes.length-read);
            if(n<0)throw new java.io.EOFException("SOCKS closed");
            read+=n;
        }
    }
}