package com.tapmemory.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MacroAccessibilityService extends AccessibilityService {
    static volatile MacroAccessibilityService instance;
    volatile String state="Служба активна";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean recording=false,playing=false,collapsed=false;
    private int generation=0,stepIndex=0,loop=0,limit=1,gapMs=1200,ipMode=0;
    private long lastEvent=0,lastScroll=0,lastActionStart=0;
    private int prevX=-100,prevY=-100;
    private String targetPackage="",ipUrl="",lastProxyIp="";
    private final ExecutorService proxyExecutor=Executors.newSingleThreadExecutor();
    private JSONArray snapshot=new JSONArray();
    private WindowManager manager;
    private WindowManager.LayoutParams panelLayout;
    private LinearLayout panel;
    private TextView handle;
    private Button recordButton,playButton,stopButton;

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        state="Служба активна. Можно записывать.";
        buildPanel();
    }
    @Override public void onInterrupt(){stopAll();}
    @Override public void onDestroy(){
        stopAll();
        if(manager!=null&&panel!=null){
            try{manager.removeView(panel);}catch(Exception ignored){}
        }
        io.shutdownNow();proxyExecutor.shutdownNow();instance=null;super.onDestroy();
    }

    private static String limited(CharSequence s){
        if(s==null)return "";
        String value=s.toString().trim().replace('\n',' ');
        return value.length()>110?value.substring(0,110):value;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!recording||event==null)return;
        CharSequence p=event.getPackageName();
        if(p==null)return;
        String pkg=p.toString();
        if(pkg.equals(getPackageName())||pkg.equals("com.android.systemui")||
            pkg.contains("inputmethod")||pkg.contains("keyboard"))return;
        int type=event.getEventType();
        if(type!=AccessibilityEvent.TYPE_VIEW_CLICKED &&
           type!=AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
           type!=AccessibilityEvent.TYPE_VIEW_SCROLLED)return;
        AccessibilityNodeInfo node=event.getSource();
        if(node==null)return;
        if(node.isPassword()){node.recycle();return;}
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        if(bounds.isEmpty()){node.recycle();return;}
        long now=event.getEventTime();
        int x=bounds.centerX(),y=bounds.centerY();
        boolean scroll=type==AccessibilityEvent.TYPE_VIEW_SCROLLED;
        if(scroll){
            if(now-lastScroll<440){node.recycle();return;}
        }else if(now-lastEvent<200&&Math.abs(x-prevX)<12&&Math.abs(y-prevY)<12){
            node.recycle();return;
        }
        long delay=lastEvent==0?250:Math.max(0,Math.min(3600000,now-lastEvent));
        JSONObject step=MacroStore.step(scroll?"scroll":type==AccessibilityEvent.TYPE_VIEW_LONG_CLICKED?"long":"tap",delay);
        MacroStore.put(step,"x",x);
        MacroStore.put(step,"y",y);
        android.util.DisplayMetrics d=getResources().getDisplayMetrics();
        MacroStore.put(step,"rx",d.widthPixels==0?0.5:(double)x/d.widthPixels);
        MacroStore.put(step,"ry",d.heightPixels==0?0.5:(double)y/d.heightPixels);
        MacroStore.put(step,"id",limited(node.getViewIdResourceName()));
        MacroStore.put(step,"cls",limited(node.getClassName()));
        if(!node.isEditable()){
            MacroStore.put(step,"label",limited(node.getText()));
            MacroStore.put(step,"desc",limited(node.getContentDescription()));
        }
        if(scroll){
            int delta=android.os.Build.VERSION.SDK_INT>=28?event.getScrollDeltaY():0;
            if(delta==0){node.recycle();return;}
            MacroStore.put(step,"direction",delta>0?"up":"down");
            MacroStore.put(step,"amount",Math.max(170,Math.min(900,Math.abs(delta))));
            lastScroll=now;
        }
        node.recycle();
        if(targetPackage.isEmpty()){
            targetPackage=pkg;MacroStore.setTarget(this,pkg);
        }
        MacroStore.add(this,step);
        lastEvent=now;prevX=x;prevY=y;
        state="Запись: "+MacroStore.steps(this).length()+" действий";
        updatePanel();
    }

    public void startRecording(boolean clear){
        stopAll();
        if(clear){MacroStore.save(this,new JSONArray());MacroStore.setTarget(this,"");}
        targetPackage=MacroStore.target(this);
        recording=true;lastEvent=0;lastScroll=0;prevX=-100;prevY=-100;
        state="Запись включена. Нажмите ■ для остановки.";
        updatePanel();
    }

    public void startPlayback(){
        stopAll();
        snapshot=MacroStore.steps(this);
        if(snapshot.length()==0){state="Сценарий пуст.";updatePanel();return;}
        String repeatValue=MacroStore.pref(this).getString("repeats","1");
        String gapValue=MacroStore.pref(this).getString("pause","1.2");
        try{limit=Math.max(0,Math.min(100000,Integer.parseInt(repeatValue.trim())));}
        catch(Exception e){limit=1;}
        try{gapMs=(int)Math.max(0,Math.min(600000,Double.parseDouble(gapValue.trim().replace(',','.'))*1000));}
        catch(Exception e){gapMs=1200;}
        ipMode=MacroStore.pref(this).getInt("mode",0);
        if(ipMode==2){
            try{
                List<ProxyList.Endpoint> profiles=ProxyList.fromSettings(this);
                if(profiles.isEmpty()){state="Нет SOCKS5 прокси.";updatePanel();return;}
                if(ProxyVpnService.instance==null){
                    state="Включите VPN через кнопку запуска в TapMemory.";updatePanel();return;
                }
            }catch(Exception e){state=e.getMessage();updatePanel();return;}
        }
        lastProxyIp="";
        ipUrl=MacroStore.pref(this).getString("url","");
        if(ipMode==1&&!ipUrl.startsWith("https://")){
            state="Для смены IP нужна HTTPS-команда роутера или провайдера.";updatePanel();return;
        }
        targetPackage=MacroStore.target(this);
        stepIndex=0;loop=0;playing=true;
        state="Запуск сценария…";updatePanel();
        if(!targetPackage.isEmpty()){
            try{
                Intent launch=getPackageManager().getLaunchIntentForPackage(targetPackage);
                if(launch!=null){
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(launch);
                }
            }catch(Exception ignored){}
        }
        handler.postDelayed(this::startLoop,1100);
    }

    public void stopAll(){
        generation++;recording=false;playing=false;
        handler.removeCallbacksAndMessages(null);
        state="Остановлено";updatePanel();
    }
    private void fail(String message){
        generation++;playing=false;recording=false;
        handler.removeCallbacksAndMessages(null);
        state="Ошибка: "+message;updatePanel();
    }
    private void startLoop(){
        if(!playing)return;
        if(limit!=0&&loop>=limit){
            playing=false;state="Готово: "+loop+" повторов.";updatePanel();return;
        }
        loop++;stepIndex=0;lastActionStart=0;
        state="Повтор "+loop+(limit==0?" / ∞":" / "+limit);updatePanel();
        if(ipMode==1)changeIpAndVerify();
        else if(ipMode==2)rotateProxy();
        else handler.postDelayed(this::nextStep,250);
    }
    private void nextStep(){
        if(!playing)return;
        if(stepIndex>=snapshot.length()){
            handler.postDelayed(this::startLoop,gapMs);return;
        }
        JSONObject step=snapshot.optJSONObject(stepIndex);
        if(step==null){stepIndex++;handler.post(this::nextStep);return;}
        long delay=Math.max(0,Math.min(3600000,step.optLong("delay",250)));
        long elapsed=lastActionStart==0?0:SystemClock.uptimeMillis()-lastActionStart;
        long wait=lastActionStart==0?Math.min(1000,delay):Math.max(0,delay-elapsed);
        handler.postDelayed(()->attemptStep(step,SystemClock.uptimeMillis()+12000),wait);
    }
    private boolean matchingPackage(AccessibilityNodeInfo root){
        if(targetPackage.isEmpty())return true;
        CharSequence pkg=root.getPackageName();
        return pkg!=null && targetPackage.equals(pkg.toString());
    }
    private void attemptStep(JSONObject step,long deadline){
        if(!playing)return;
        String type=step.optString("type","");
        if(type.equals("wait")){
            lastActionStart=SystemClock.uptimeMillis();stepIndex++;nextStep();return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||!matchingPackage(root)){
            if(root!=null)root.recycle();
            retry(step,deadline,"Ожидание нужного приложения");return;
        }
        if(type.equals("text")){
            AccessibilityNodeInfo field=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(field==null||!field.isEditable()||!field.isFocused()){
                if(field!=null)field.recycle();
                root.recycle();retry(step,deadline,"Ожидание поля ввода");return;
            }
            if(field.isPassword()){
                field.recycle();root.recycle();fail("Ввод в парольное поле запрещён.");return;
            }
            String text=step.optString("text","");
            boolean ok=false;
            try{
                ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if(clipboard!=null){
                    clipboard.setPrimaryClip(ClipData.newPlainText("TapMemory",text));
                    ok=field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                }
                if(!ok){
                    Bundle args=new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
                    ok=field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
                }
            }catch(Exception ignored){}
            field.recycle();root.recycle();
            if(!ok){fail("Chrome не разрешил вставку текста.");return;}
            completeStep();return;
        }
        AccessibilityNodeInfo match=findMatch(root,step);
        boolean semantic=hasLocator(step);
        int x=resolveX(step),y=resolveY(step);
        if(match==null&&semantic&&("tap".equals(type)||"long".equals(type))){
            root.recycle();retry(step,deadline,"Ожидание элемента страницы");return;
        }
        if(match!=null){
            Rect rect=new Rect();match.getBoundsInScreen(rect);
            if(!rect.isEmpty()){x=rect.centerX();y=rect.centerY();}
            match.recycle();
        }
        root.recycle();
        if("tap".equals(type)||"long".equals(type)){
            Path path=new Path();path.moveTo(x,y);
            long hold=type.equals("long")?650:65;
            lastActionStart=SystemClock.uptimeMillis();
            gesture(new GestureDescription.StrokeDescription(path,0,hold),()->{
                stepIndex++;nextStep();
            });
        }else if("scroll".equals(type)){
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();
            int range=Math.max(190,Math.min(d.heightPixels/2,step.optInt("amount",350)));
            int fromY=Math.max(90,Math.min(d.heightPixels-90,y+("up".equals(step.optString("direction"))?range/2:-range/2)));
            int toY=Math.max(90,Math.min(d.heightPixels-90,y+("up".equals(step.optString("direction"))?-range/2:range/2)));
            Path path=new Path();path.moveTo(x,fromY);path.lineTo(x,toY);
            lastActionStart=SystemClock.uptimeMillis();
            gesture(new GestureDescription.StrokeDescription(path,0,360),()->{
                stepIndex++;nextStep();
            });
        }else{fail("Неизвестное действие: "+type);}
    }
    private void completeStep(){
        lastActionStart=SystemClock.uptimeMillis();
        stepIndex++;handler.post(this::nextStep);
    }
    private void retry(JSONObject step,long deadline,String status){
        if(SystemClock.uptimeMillis()>=deadline){
            fail(status+": превышено 12 секунд на шаге "+(stepIndex+1));return;
        }
        state=status+" · шаг "+(stepIndex+1)+"/"+snapshot.length();
        updatePanel();
        handler.postDelayed(()->attemptStep(step,deadline),270);
    }
    private void gesture(GestureDescription.StrokeDescription stroke,Runnable onSuccess){
        GestureDescription.Builder builder=new GestureDescription.Builder();
        builder.addStroke(stroke);
        boolean dispatched=dispatchGesture(builder.build(),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){
                if(playing)onSuccess.run();
            }
            @Override public void onCancelled(GestureDescription g){
                if(playing)fail("Жест отменён Android. Проверьте экран.");
            }
        },handler);
        if(!dispatched)fail("Android отклонил действие.");
    }
    private boolean hasLocator(JSONObject step){
        return !step.optString("id").isEmpty() || !step.optString("label").isEmpty() ||
            !step.optString("desc").isEmpty();
    }
    private int resolveX(JSONObject step){
        android.util.DisplayMetrics d=getResources().getDisplayMetrics();
        if(step.has("rx"))return (int)(step.optDouble("rx",0.5)*d.widthPixels);
        return step.optInt("x",d.widthPixels/2);
    }
    private int resolveY(JSONObject step){
        android.util.DisplayMetrics d=getResources().getDisplayMetrics();
        if(step.has("ry"))return (int)(step.optDouble("ry",0.5)*d.heightPixels);
        return step.optInt("y",d.heightPixels/2);
    }
    private AccessibilityNodeInfo findMatch(AccessibilityNodeInfo root,JSONObject step){
        if(!hasLocator(step))return null;
        String id=step.optString("id"),label=step.optString("label"),desc=step.optString("desc");
        String cls=step.optString("cls");
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best=null;int bestScore=0,visited=0;
        while(!queue.isEmpty()&&visited++<900){
            AccessibilityNodeInfo n=queue.removeFirst();
            int score=0;
            String ni=limited(n.getViewIdResourceName());
            String nl=limited(n.getText());
            String nd=limited(n.getContentDescription());
            String nc=limited(n.getClassName());
            if(!id.isEmpty()&&id.equals(ni))score+=120;
            if(!label.isEmpty()&&label.equals(nl))score+=90;
            if(!desc.isEmpty()&&desc.equals(nd))score+=90;
            if(score>=90){
                if(!cls.isEmpty()&&cls.equals(nc))score+=10;
                if(n.isVisibleToUser())score+=15;
                if(n.isClickable())score+=5;
                Rect rect=new Rect();n.getBoundsInScreen(rect);
                if(!rect.isEmpty()){
                    int dx=Math.abs(rect.centerX()-resolveX(step));
                    int dy=Math.abs(rect.centerY()-resolveY(step));
                    if(dx<75&&dy<75)score+=8;
                }
                if(score>bestScore){
                    if(best!=null)best.recycle();
                    best=AccessibilityNodeInfo.obtain(n);bestScore=score;
                }
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null)queue.addLast(child);
            }
            n.recycle();
        }
        while(!queue.isEmpty())queue.removeFirst().recycle();
        return best;
    }
    private void rotateProxy(){
        final List<ProxyList.Endpoint> profiles;
        try{profiles=ProxyList.fromSettings(this);}
        catch(Exception e){fail("Некорректные прокси: "+e.getMessage());return;}
        if(profiles.isEmpty()){fail("Список прокси пуст");return;}
        int selected=(loop-1)%profiles.size();
        ProxyList.Endpoint ep=profiles.get(selected);
        state="Подключаю SOCKS5 "+(selected+1)+"/"+profiles.size()+"…";
        updatePanel();
        boolean accepted=ProxyVpnService.switchProxy(ep,targetPackage);
        if(!accepted){fail("VPN не запущен. Включите через главный экран.");return;}
        final int epoch=generation;
        waitProxy(ep,epoch,SystemClock.uptimeMillis()+16000);
    }
    private void waitProxy(ProxyList.Endpoint ep,int epoch,long deadline){
        if(!playing||epoch!=generation)return;
        if(SystemClock.uptimeMillis()>deadline){
            fail("SOCKS5 не подключился: "+ProxyVpnService.status);return;
        }
        if(!ProxyVpnService.ready||!ep.toString().equals(ProxyVpnService.active)){
            handler.postDelayed(()->waitProxy(ep,epoch,deadline),400);return;
        }
        state="Проверяю IP через SOCKS5…";updatePanel();
        proxyExecutor.execute(()->{
            String exit=ProxyProbe.exitIp(ep);
            handler.post(()->{
                if(!playing||epoch!=generation)return;
                if(exit==null){
                    fail("Прокси "+ep+" не прошёл проверку HTTPS. Подключение остановлено.");
                    return;
                }
                if(profilesCount()>1&&exit.equals(lastProxyIp)){
                    fail("Внешний IP не изменился при переключении прокси. Повтор остановлен.");
                    return;
                }
                lastProxyIp=exit;
                state="SOCKS5 активен. Внешний IP: "+exit;
                updatePanel();
                handler.postDelayed(this::nextStep,500);
            });
        });
    }
    private int profilesCount(){
        try{return ProxyList.fromSettings(this).size();}
        catch(Exception ignored){return 0;}
    }
    // The device stays associated with Wi-Fi. IP rotation requires a router/provider HTTPS API.
    private void changeIpAndVerify(){
        final int epoch=generation;
        state="Проверяю текущий внешний IP…";updatePanel();
        io.execute(()->{
            String before=readIp();
            if(before==null){
                handler.post(()->{if(playing&&epoch==generation)fail("Не удалось проверить внешний IP.");});
                return;
            }
            boolean accepted=false;
            try{
                HttpURLConnection conn=(HttpURLConnection)new URL(ipUrl).openConnection();
                conn.setRequestMethod("GET");conn.setConnectTimeout(9000);conn.setReadTimeout(9000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Cache-Control","no-cache");
                int code=conn.getResponseCode();accepted=code>=200&&code<300;
                conn.disconnect();
            }catch(Exception ignored){}
            if(!accepted){
                handler.post(()->{if(playing&&epoch==generation)fail("Роутер/провайдер не принял команду смены IP.");});
                return;
            }
            boolean changed=false;
            for(int k=0;k<14&&epoch==generation;k++){
                try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                String after=readIp();
                if(after!=null&&!after.equals(before)){changed=true;break;}
            }
            final boolean result=changed;
            handler.post(()->{
                if(!playing||epoch!=generation)return;
                if(!result){fail("IP не изменился. Проверьте динамический IP и API роутера.");return;}
                state="IP изменён, Wi-Fi остаётся включённым.";updatePanel();
                handler.postDelayed(this::nextStep,800);
            });
        });
    }
    private String readIp(){
        try{
            HttpURLConnection c=(HttpURLConnection)new URL("https://api.ipify.org").openConnection();
            c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setUseCaches(false);
            c.setRequestProperty("Cache-Control","no-cache");
            String ip;
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){
                ip=r.readLine();
            }finally{c.disconnect();}
            if(ip==null||ip.length()>60||ip.trim().isEmpty())return null;
            return ip.trim();
        }catch(Exception ignored){return null;}
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private Button control(String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setTextSize(14);
        b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);
        b.setOnClickListener(v->action.run());
        panel.addView(b,new LinearLayout.LayoutParams(dp(41),dp(42)));
        return b;
    }
    private void buildPanel(){
        try{
            manager=(WindowManager)getSystemService(WINDOW_SERVICE);
            panel=new LinearLayout(this);panel.setOrientation(LinearLayout.HORIZONTAL);
            panel.setGravity(Gravity.CENTER_VERTICAL);
            panel.setPadding(dp(3),dp(2),dp(3),dp(2));
            panel.setBackgroundColor(0xee172a42);
            handle=new TextView(this);handle.setTextColor(0xffffffff);
            handle.setText(" TM ");handle.setTextSize(13);handle.setGravity(Gravity.CENTER);
            handle.setPadding(dp(7),0,dp(7),0);
            panel.addView(handle,new LinearLayout.LayoutParams(dp(48),dp(42)));
            recordButton=control("●",()->{if(recording)stopAll();else startRecording(false);});
            playButton=control("▶",()->{if(!playing)startPlayback();});
            stopButton=control("■",this::stopAll);
            panelLayout=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
            panelLayout.gravity=Gravity.TOP|Gravity.START;panelLayout.x=dp(6);panelLayout.y=dp(140);
            handle.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY;int originalX,originalY;
                @Override public boolean onTouch(View v,MotionEvent e){
                    switch(e.getAction()){
                        case MotionEvent.ACTION_DOWN:
                            downX=e.getRawX();downY=e.getRawY();
                            originalX=panelLayout.x;originalY=panelLayout.y;return true;
                        case MotionEvent.ACTION_MOVE:
                            panelLayout.x=originalX+(int)(e.getRawX()-downX);
                            panelLayout.y=originalY+(int)(e.getRawY()-downY);
                            try{manager.updateViewLayout(panel,panelLayout);}catch(Exception ignored){}
                            return true;
                        case MotionEvent.ACTION_UP:
                            if(Math.abs(e.getRawX()-downX)<dp(6)&&Math.abs(e.getRawY()-downY)<dp(6)){
                                collapsed=!collapsed;
                                int visible=collapsed?View.GONE:View.VISIBLE;
                                recordButton.setVisibility(visible);
                                playButton.setVisibility(visible);
                                stopButton.setVisibility(visible);
                                handle.setText(collapsed?" TM ":"≡");
                            }
                            return true;
                    }return false;
                }
            });
            manager.addView(panel,panelLayout);
        }catch(Exception e){
            state="Служба работает без панели: "+e.getClass().getSimpleName();
        }
    }
    private void updatePanel(){
        handler.post(()->{
            if(handle!=null&&!collapsed)
                handle.setText(recording?"REC":playing?("▶"+loop):"≡");
        });
    }
}