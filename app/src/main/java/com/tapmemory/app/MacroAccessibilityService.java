package com.tapmemory.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.View;
import android.view.MotionEvent;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayDeque;
import java.util.Locale;

public class MacroAccessibilityService extends AccessibilityService {
    static volatile MacroAccessibilityService instance;
    volatile String state="Готово. Запишите действия или запустите сценарий.";
    private boolean recording=false, playing=false;
    private long lastRecorded=0, lastScroll=0; private int lastX=-1,lastY=-1;
    private Handler h=new Handler(Looper.getMainLooper());
    private ExecutorService executor=Executors.newSingleThreadExecutor();
    private JSONArray playSteps;
    private int currentStep=0, iteration=0, repeats=1, interval=1200, rotation=0;
    private String rotationUrl="";
    private WindowManager wm; private LinearLayout overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView overlayLabel;

    @Override protected void onServiceConnected(){
        super.onServiceConnected();instance=this;createOverlay();
        state="Служба включена. Готово к записи.";
    }
    @Override public void onInterrupt(){stopAll();}
    @Override public void onDestroy(){
        stopAll(); if(overlay!=null){try{wm.removeView(overlay);}catch(Exception ignored){}}
        instance=null;executor.shutdownNow();super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!recording || event==null)return;
        CharSequence pkg=event.getPackageName();
        if(pkg==null || pkg.toString().equals(getPackageName()) ||
                pkg.toString().equals("com.android.systemui"))return;
        int type=event.getEventType();
        long now=SystemClock.uptimeMillis();
        if(type==AccessibilityEvent.TYPE_VIEW_CLICKED){
            AccessibilityNodeInfo src=event.getSource();
            if(src==null)return;
            if(src.isPassword()){src.recycle();return;}
            Rect r=new Rect();src.getBoundsInScreen(r);src.recycle();
            if(r.isEmpty() || r.centerX()<0 || r.centerY()<0)return;
            int x=r.centerX(),y=r.centerY();
            if(now-lastRecorded<250 && Math.abs(x-lastX)<8 && Math.abs(y-lastY)<8)return;
            JSONObject j=MacroStore.step("tap",lastRecorded==0?500:now-lastRecorded);
            MacroStore.put(j,"x",x);MacroStore.put(j,"y",y);
            MacroStore.add(this,j);lastRecorded=now;lastX=x;lastY=y;
            state="Запись: "+MacroStore.steps(this).length()+" шагов";
        } else if(type==AccessibilityEvent.TYPE_VIEW_SCROLLED && now-lastScroll>650){
            int dy=android.os.Build.VERSION.SDK_INT>=28?event.getScrollDeltaY():0;
            if(dy==0)return;
            AccessibilityNodeInfo src=event.getSource();Rect r=new Rect();
            if(src!=null){src.getBoundsInScreen(r);src.recycle();}
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();
            int x=r.isEmpty()?d.widthPixels/2:r.centerX();
            int y=r.isEmpty()?d.heightPixels/2:r.centerY();
            JSONObject j=MacroStore.step("swipe",lastRecorded==0?400:now-lastRecorded);
            MacroStore.put(j,"x",x);MacroStore.put(j,"y",y);
            MacroStore.put(j,"direction",dy>0?"up":"down");
            MacroStore.add(this,j);
            lastRecorded=now;lastScroll=now;
            state="Запись: "+MacroStore.steps(this).length()+" шагов";
        }
        showOverlay();
    }
    public void beginRecord(){
        stopAll(); MacroStore.save(this,new JSONArray());
        recording=true;lastRecorded=0;lastScroll=0;lastX=-1;lastY=-1;
        state="● Запись: перейдите в приложение и нажимайте элементы.";
        showOverlay();
    }
    public void beginPlay(){
        stopAll(); playSteps=MacroStore.steps(this);
        if(playSteps.length()==0){state="Сценарий пуст";return;}
        try{repeats=Integer.parseInt(MacroStore.pref(this).getString("repeats","1"));}
        catch(Exception e){repeats=1;}
        try{interval=Integer.parseInt(MacroStore.pref(this).getString("pause","1200"));}
        catch(Exception e){interval=1200;}
        repeats=Math.max(0,Math.min(100000,repeats));
        interval=Math.max(100,Math.min(60000,interval));
        rotation=MacroStore.pref(this).getInt("mode",0);
        rotationUrl=MacroStore.pref(this).getString("url","");
        iteration=0;currentStep=0;playing=true;
        state="▶ Запуск сценария";
        showOverlay();h.postDelayed(this::nextCycle,800);
    }
    public void stopAll(){
        recording=false;playing=false;h.removeCallbacksAndMessages(null);
        state="■ Остановлено";showOverlay();
    }
    private void nextCycle(){
        if(!playing)return;
        if(repeats>0 && iteration>=repeats){
            playing=false;state="✓ Выполнено "+iteration+" повторов";showOverlay();return;
        }
        iteration++;currentStep=0;
        state="Повтор "+iteration+(repeats==0?" / ∞":" / "+repeats);
        showOverlay();
        if(rotation==1)rotateAirplane(true);
        else if(rotation==2)rotateViaUrl();
        else h.postDelayed(this::nextStep,120);
    }
    private void nextStep(){
        if(!playing)return;
        if(currentStep>=playSteps.length()){
            h.postDelayed(this::nextCycle,interval);return;
        }
        JSONObject j=playSteps.optJSONObject(currentStep++);
        if(j==null){h.post(this::nextStep);return;}
        long delay=Math.max(100,Math.min(60000,j.optLong("delay",300)));
        h.postDelayed(()->performStep(j),delay);
    }
    private void performStep(JSONObject j){
        if(!playing)return;
        String type=j.optString("type");
        if(type.equals("wait")){h.post(this::nextStep);return;}
        if(type.equals("text")){
            if(!insertText(j.optString("text"))){
                fail("Не найдено активное поле для вставки текста");return;
            }
            h.postDelayed(this::nextStep,200);return;
        }
        int x=j.optInt("x"),y=j.optInt("y");
        if(type.equals("tap")){
            Path p=new Path();p.moveTo(x,y);
            GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,85);
            sendGesture(stroke,()->h.postDelayed(this::nextStep,120));
        } else if(type.equals("swipe")){
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();
            int dist=Math.max(140,d.heightPixels/3);
            boolean up="up".equals(j.optString("direction"));
            int fromY=Math.max(70,Math.min(d.heightPixels-70,y+(up?dist/2:-dist/2)));
            int toY=Math.max(70,Math.min(d.heightPixels-70,y+(up?-dist/2:dist/2)));
            Path p=new Path();p.moveTo(x,fromY);p.lineTo(x,toY);
            sendGesture(new GestureDescription.StrokeDescription(p,0,350),()->h.post(this::nextStep));
        } else h.post(this::nextStep);
    }
    private void sendGesture(GestureDescription.StrokeDescription stroke,Runnable done){
        GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(stroke);
        boolean sent=dispatchGesture(b.build(),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gesture){
                if(playing)done.run();
            }
            @Override public void onCancelled(GestureDescription gesture){
                if(playing)fail("Android отменил жест");
            }
        },h);
        if(!sent)fail("Не удалось выполнить касание");
    }
    private boolean insertText(String text){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        AccessibilityNodeInfo node=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(node==null)node=findEditable(root);
        boolean ok=false;
        if(node!=null){
            Bundle data=new Bundle();
            data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            ok=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,data);
            if(!ok){
                ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if(cm!=null){
                    cm.setPrimaryClip(ClipData.newPlainText("TapMemory",text));
                    ok=node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                }
            }
            node.recycle();
        }
        root.recycle();return ok;
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root){
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int limit=0;
        while(!queue.isEmpty() && ++limit<500){
            AccessibilityNodeInfo n=queue.removeFirst();
            if(n.isEditable() && n.isFocused()) {
                while(!queue.isEmpty())queue.removeFirst().recycle();
                return n;
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null)queue.addLast(child);
            }
            n.recycle();
        }
        while(!queue.isEmpty())queue.removeFirst().recycle();
        return null;
    }
    private void rotateViaUrl(){
        if(!rotationUrl.startsWith("https://")){
            fail("Нужна HTTPS-ссылка смены IP");return;
        }
        state="Смена IP: вызов команды провайдера…";showOverlay();
        executor.execute(()->{
            boolean ok=false;String message="";
            try{
                HttpURLConnection c=(HttpURLConnection)new URL(rotationUrl).openConnection();
                c.setRequestMethod("GET");c.setConnectTimeout(8000);c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(false);
                int code=c.getResponseCode();ok=code>=200&&code<300;
                message="Ответ HTTP "+code;c.disconnect();
            }catch(Exception e){message=e.getClass().getSimpleName();}
            final boolean result=ok;final String m=message;
            h.post(()->{
                if(!playing)return;
                if(!result){fail("Не удалось отправить команду смены IP: "+m);return;}
                state="Команда смены IP отправлена; ожидание сети 6 с";
                showOverlay();h.postDelayed(this::nextStep,6000);
            });
        });
    }
    private void rotateAirplane(boolean toOn){
        if(!playing)return;
        state=toOn?"Смена IP: включаю авиарежим…":"Смена IP: выключаю авиарежим…";
        showOverlay();
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        h.postDelayed(()->{
            if(!playing)return;
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null){fail("Не открылась шторка быстрых настроек");return;}
            AccessibilityNodeInfo tile=findAirplane(root);
            if(tile==null){root.recycle();fail("Кнопка «Авиарежим» не найдена. Режим не подходит этому телефону.");return;}
            boolean clicked=clickParent(tile);
            tile.recycle();root.recycle();
            if(!clicked){fail("Не получилось переключить авиарежим");return;}
            if(toOn){
                h.postDelayed(()->rotateAirplane(false),2300);
            }else{
                h.postDelayed(()->{
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    state="Ожидание повторного подключения мобильной сети (9 с)…";
                    showOverlay();h.postDelayed(this::nextStep,9000);
                },1100);
            }
        },950);
    }
    private AccessibilityNodeInfo findAirplane(AccessibilityNodeInfo root){
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        int limit=0;
        while(!q.isEmpty()&&++limit<600){
            AccessibilityNodeInfo n=q.removeFirst();
            String a=((n.getText()==null?"":n.getText().toString())+" "+(n.getContentDescription()==null?"":n.getContentDescription().toString())).toLowerCase(Locale.ROOT);
            if(a.contains("airplane")||a.contains("flight mode")||a.contains("авиарежим")
                ||a.contains("режим полёт")||a.contains("режим полет")
                ||a.contains("режим польоту")||a.contains("літак")){
                while(!q.isEmpty())q.removeFirst().recycle();
                return n;
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);if(child!=null)q.add(child);
            }
            n.recycle();
        }
        while(!q.isEmpty())q.removeFirst().recycle();
        return null;
    }
    private boolean clickParent(AccessibilityNodeInfo node){
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(node);
        boolean ok=false;
        for(int i=0;i<5 && n!=null;i++){
            if(n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){ok=true;break;}
            AccessibilityNodeInfo p=n.getParent();n.recycle();n=p;
        }
        if(n!=null)n.recycle();
        return ok;
    }
    private void fail(String message){
        playing=false;recording=false;state="Ошибка: "+message;showOverlay();
    }
    private void createOverlay(){
        try{
            wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            overlay=new LinearLayout(this);
            overlay.setOrientation(LinearLayout.HORIZONTAL);
            overlay.setGravity(Gravity.CENTER_VERTICAL);
            overlay.setBackgroundColor(0xee24384e);
            overlay.setPadding(10,4,10,4);
            overlayLabel=new TextView(this); overlayLabel.setTextColor(0xffffffff);
            overlayLabel.setText("TapMemory");overlayLabel.setTextSize(12);
            overlay.addView(overlayLabel);
            Button record=new Button(this);record.setText("●"); record.setOnClickListener(v->{
                if(recording)stopAll();else beginRecord();
            });overlay.addView(record,new LinearLayout.LayoutParams(85,85));
            Button run=new Button(this);run.setText("▶");run.setOnClickListener(v->{
                if(!playing)beginPlay();
            });overlay.addView(run,new LinearLayout.LayoutParams(85,85));
            Button stop=new Button(this);stop.setText("■");
            stop.setOnClickListener(v->stopAll());overlay.addView(stop,new LinearLayout.LayoutParams(85,85));
            overlayParams=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            overlayParams.gravity=Gravity.TOP|Gravity.START;
            overlayParams.x=8;overlayParams.y=175;
            overlayLabel.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY;int startX,startY;
                @Override public boolean onTouch(View v,MotionEvent e){
                    switch(e.getAction()){
                        case MotionEvent.ACTION_DOWN:
                            downX=e.getRawX();downY=e.getRawY();startX=overlayParams.x;startY=overlayParams.y;return true;
                        case MotionEvent.ACTION_MOVE:
                            overlayParams.x=startX+(int)(e.getRawX()-downX);
                            overlayParams.y=startY+(int)(e.getRawY()-downY);
                            wm.updateViewLayout(overlay,overlayParams);return true;
                    }
                    return true;
                }
            });
            wm.addView(overlay,overlayParams);
        }catch(Exception e){state="Служба работает без плавающей панели: "+e.getMessage();}
    }
    private void showOverlay(){
        if(overlayLabel!=null)h.post(()->{
            if(overlayLabel!=null)overlayLabel.setText(recording?"● REC":playing?"▶ "+iteration:"TapMemory");
        });
    }
}