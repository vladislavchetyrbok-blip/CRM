package com.tapmemory.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProxyVpnService extends VpnService {
    private static native boolean TProxyStartService(String configPath,int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();
    static volatile ProxyVpnService instance;
    static volatile boolean ready=false;
    static volatile String status="Прокси выключен";
    static volatile String active="";

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static final String CONNECT="com.tapmemory.proxy.CONNECT";
    public static final String DISCONNECT="com.tapmemory.proxy.DISCONNECT";
    public static final String HOST="host";
    public static final String PORT="port";
    public static final String TARGET="target";
    private final ExecutorService engine=Executors.newSingleThreadExecutor();
    private ParcelFileDescriptor tun;
    private String current="";
    private int serial=0;

    static void request(Context context,ProxyList.Endpoint endpoint,String target) {
        Intent intent=new Intent(context,ProxyVpnService.class);
        intent.setAction(CONNECT);
        intent.putExtra(HOST,endpoint.host);
        intent.putExtra(PORT,endpoint.port);
        intent.putExtra(TARGET,target);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
        else context.startService(intent);
    }

    static boolean switchProxy(ProxyList.Endpoint ep,String target,String username,String password){
        ProxyVpnService service=instance;
        if(service==null)return false;
        service.reconfigure(ep.host,ep.port,target,username,password);
        return true;
    }

    @Override public void onCreate(){
        super.onCreate();
        instance=this;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            nm.createNotificationChannel(new NotificationChannel(
                "tapmemory_vpn","TapMemory · Прокси",NotificationManager.IMPORTANCE_LOW));
        }
    }
    private void foreground(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"tapmemory_vpn")
            .setContentTitle("TapMemory · Прокси Chrome")
            .setContentText("SOCKS5-подключение активно")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending).setOngoing(true).build();
        startForeground(42,note);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&DISCONNECT.equals(intent.getAction())){
            shutdown();return START_NOT_STICKY;
        }
        foreground();
        if(intent!=null&&CONNECT.equals(intent.getAction())){
            String host=intent.getStringExtra(HOST);
            int port=intent.getIntExtra(PORT,0);
            String target=intent.getStringExtra(TARGET);
            reconfigure(host,port,target,"","");
        }
        return START_NOT_STICKY;
    }
    private void reconfigure(String host,int port,String target,String username,String password) {
        if(host==null||port<1||port>65535){status="Ошибка конфигурации прокси";return;}
        final int job=++serial;
        ready=false;status="Переключение прокси…";
        final String targetPkg=(target==null||target.trim().isEmpty())?"com.android.chrome":target;
        engine.execute(()->{
            if(tun!=null){
                try{TProxyStopService();}catch(Throwable ignored){}
                try{tun.close();}catch(Exception ignored){}
                tun=null;
            }
            if(job!=serial)return;
            try{
                VpnService.Builder b=new VpnService.Builder();
                b.setSession("TapMemory SOCKS5 "+host);
                b.setMtu(8500);
                b.setBlocking(false);
                b.addAddress("198.18.0.1",32);
                b.addRoute("0.0.0.0",0);
                b.addAddress("fc00::1",128);
                b.addRoute("::",0);
                b.addDnsServer("198.18.0.2");
                // Only the target application is routed; our own outbound proxy socket
                // is never re-injected into the tunnel.
                try{
                    b.addAllowedApplication(targetPkg);
                }catch(PackageManager.NameNotFoundException error){
                    status="Приложение "+targetPkg+" не установлено";return;
                }
                tun=b.establish();
                if(tun==null){status="Нет разрешения Android VPN";return;}
                File conf=new File(getCacheDir(),"tapmemory-socks.conf");
                String yaml="misc:\n  task-stack-size: 81920\n"+
                    "tunnel:\n  mtu: 8500\n  icmp: 'reply'\n"+
                    "socks5:\n  port: "+port+"\n  address: '"+host+"'\n  udp: 'tcp'\n";
                if(!username.isEmpty()&&!password.isEmpty()){
                    if(username.length()>100||password.length()>100||
                       username.contains("'")||password.contains("'"))
                        throw new IllegalArgumentException("Некорректный Tor token");
                    yaml+="  username: '"+username+"'\n  password: '"+password+"'\n";
                }
                yaml+="mapdns:\n  address: 198.18.0.2\n  port: 53\n"+
                    "  network: 240.0.0.0\n  netmask: 240.0.0.0\n"+
                    "  cache-size: 10000\n";
                try(FileOutputStream stream=new FileOutputStream(conf,false)){
                    stream.write(yaml.getBytes(StandardCharsets.UTF_8));
                }
                if(job!=serial)return;
                boolean started=TProxyStartService(conf.getAbsolutePath(),tun.getFd());
                ready=started;active=host+":"+port;
                status=started?"Прокси подключён: "+active:"Движок SOCKS5 не запустился";
            }catch(Throwable e){
                ready=false;status="Ошибка VPN: "+e.getClass().getSimpleName();
            }
        });
    }
    private void shutdown(){
        ++serial;ready=false;active="";
        engine.execute(()->{
            try{TProxyStopService();}catch(Throwable ignored){}
            if(tun!=null){try{tun.close();}catch(Exception ignored){}tun=null;}
            status="Прокси выключен";
            stopForeground(true);stopSelf();
        });
    }
    @Override public void onRevoke(){shutdown();super.onRevoke();}
    @Override public void onDestroy(){
        ++serial;ready=false;
        try{TProxyStopService();}catch(Throwable ignored){}
        if(tun!=null){try{tun.close();}catch(Exception ignored){}tun=null;}
        engine.shutdownNow();instance=null;
        super.onDestroy();
    }
}