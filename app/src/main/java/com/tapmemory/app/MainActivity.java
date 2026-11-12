package com.tapmemory.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private LinearLayout body, list;
    private TextView status;
    private EditText repeats, pause, url;
    private Spinner ipMode;
    private final Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(13,19,30));
        getWindow().setNavigationBarColor(Color.rgb(13,19,30));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17,24,39));
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18),dp(20),dp(18),dp(30));
        scroll.addView(body); setContentView(scroll);
        heading("TapMemory",26);
        note("Автокликер: записывает нажатия и прокрутки, воспроизводит действия, вставляет текст и умеет запускать смену IP перед повтором.");
        status=label(""); body.addView(status);
        button("1. Включить службу специальных возможностей",()->{
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        heading("Запись действий",19);
        note("Нажмите «Запись», перейдите в нужное приложение и выполните действия. Плавающая кнопка «Стоп» завершит запись. Пароли и ввод с клавиатуры автоматически НЕ сохраняются.");
        button("● Начать запись",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s==null){ toast("Сначала включите службу TapMemory"); return; }
            s.beginRecord(); refresh();
            moveTaskToBack(true);
        });
        button("● Продолжить запись, не удаляя шаги",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s==null){toast("Включите службу TapMemory");return;}
            s.beginAppendRecord();refresh();moveTaskToBack(true);
        });
        button("■ Завершить запись",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s!=null) s.stopAll(); refresh();
        });
        button("＋ Добавить вставку текста из буфера",this::addClipboardText);
        button("＋ Добавить паузу 2 секунды",()->{
            MacroStore.add(this,MacroStore.step("wait",2000)); refresh();
        });
        heading("Воспроизведение",19);
        repeats=input("Количество повторов (0 = бесконечно)","1");
        pause=input("Пауза между повторами, мс","1200");
        heading("Смена IP перед каждым повтором",19);
        String[] modes={"Не менять IP","Авиарежим: переключить мобильную сеть (экспериментально)","HTTPS-ссылка провайдера для смены IP"};
        ipMode=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes);
        ipMode.setAdapter(adapter); body.addView(ipMode);
        url=input("HTTPS URL команды смены IP (для третьего режима)","https://");
        note("Для авиарежима используйте мобильный интернет и выключите Wi-Fi. Оператор может выдать тот же IP: гарантии смены нет. HTTPS-ссылка работает только с вашим провайдером VPN/прокси и сама по себе не меняет IP телефона.");
        int mode=MacroStore.pref(this).getInt("mode",0); ipMode.setSelection(mode);
        repeats.setText(MacroStore.pref(this).getString("repeats","1"));
        pause.setText(MacroStore.pref(this).getString("pause","1200"));
        url.setText(MacroStore.pref(this).getString("url",""));
        button("▶ Запустить сценарий",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s==null){ toast("Включите службу специальных возможностей"); return; }
            saveSettings();
            if(MacroStore.steps(this).length()==0){toast("Сначала запишите действия");return;}
            if(ipMode.getSelectedItemPosition()==2 && !url.getText().toString().trim().startsWith("https://")){
                toast("Нужна HTTPS-ссылка для смены IP"); return;
            }
            s.beginPlay();
            moveTaskToBack(true);
        });
        button("■ Остановить",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s!=null) s.stopAll(); refresh();
        });
        heading("Сохранённый сценарий",19);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        button("Удалить весь сценарий",()->new android.app.AlertDialog.Builder(this)
            .setTitle("Удалить все шаги?")
            .setPositiveButton("Удалить",(d,w)->{MacroStore.save(this,new JSONArray());refresh();})
            .setNegativeButton("Отмена",null).show());
        handler.post(new Runnable(){
            @Override public void run(){updateStatus();handler.postDelayed(this,700);}
        });
        refresh();
    }
    private void addClipboardText() {
        ClipboardManager c=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(c==null || !c.hasPrimaryClip()){toast("Буфер обмена пуст");return;}
        ClipData clip=c.getPrimaryClip();
        if(clip==null || clip.getItemCount()<1){toast("Нет текста в буфере");return;}
        CharSequence text=clip.getItemAt(0).coerceToText(this);
        if(text==null || text.length()==0){toast("Нет текста в буфере");return;}
        JSONObject step=MacroStore.step("text",350); MacroStore.put(step,"text",text.toString());
        MacroStore.add(this,step); toast("Добавлен шаг вставки текста");refresh();
    }
    private void refresh(){
        if(list==null)return;
        list.removeAllViews();
        JSONArray steps=MacroStore.steps(this);
        if(steps.length()==0){noteTo(list,"Шагов нет. Запустите запись.");return;}
        for(int i=0;i<steps.length();i++){
            final int index=i; JSONObject j=steps.optJSONObject(i);
            if(j==null)continue;
            String type=j.optString("type");
            String s;
            switch(type){
                case "tap": s="Нажатие · "+j.optInt("x")+", "+j.optInt("y");break;
                case "swipe": s="Прокрутка · "+j.optInt("x")+", "+j.optInt("y");break;
                case "text": String t=j.optString("text");
                    s="Вставить текст · "+(t.length()>48?t.substring(0,48)+"…":t); break;
                default: s="Пауза";
            }
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text=new TextView(this); text.setText((i+1)+". "+s+" ("+j.optLong("delay")+" мс)");
            text.setTextColor(Color.WHITE);text.setTextSize(14);
            row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            Button up=new Button(this);up.setText("↑");up.setMinWidth(0);up.setOnClickListener(v->{
                MacroStore.move(this,index,index-1);refresh();
            });row.addView(up,new LinearLayout.LayoutParams(dp(40),dp(48)));
            Button down=new Button(this);down.setText("↓");down.setMinWidth(0);down.setOnClickListener(v->{
                MacroStore.move(this,index,index+1);refresh();
            });row.addView(down,new LinearLayout.LayoutParams(dp(40),dp(48)));
            Button del=new Button(this);del.setMinWidth(0);del.setText("×");del.setOnClickListener(v->{
                MacroStore.remove(this,index);refresh();
            });row.addView(del,new LinearLayout.LayoutParams(dp(40),dp(48)));
            list.addView(row);
        }
    }
    private void saveSettings(){
        MacroStore.pref(this).edit().putString("repeats",repeats.getText().toString())
            .putString("pause",pause.getText().toString())
            .putString("url",url.getText().toString().trim())
            .putInt("mode",ipMode.getSelectedItemPosition()).apply();
    }
    private void updateStatus(){
        if(status==null)return;
        MacroAccessibilityService s=MacroAccessibilityService.instance;
        status.setText(s==null?"Служба выключена. Включите её в настройках.":s.state);
        status.setTextColor(s==null?0xffffb5b5:0xff8cead0);
    }
    @Override protected void onResume(){super.onResume();if(list!=null)refresh();}
    private EditText input(String hint,String defaultValue){
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);
        e.setText(defaultValue);e.setTextColor(Color.WHITE);e.setHintTextColor(0xffaab4c4);
        body.addView(e,new LinearLayout.LayoutParams(-1,dp(55)));return e;
    }
    private void heading(String s,int size){ TextView t=label(s);t.setTextSize(size);t.setTextColor(0xffe4eaff);
        t.setPadding(0,dp(14),0,dp(7));body.addView(t);}
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(15);t.setTextColor(Color.WHITE);return t;}
    private void note(String s){noteTo(body,s);}
    private void noteTo(LinearLayout dest,String s){
        TextView t=label(s);t.setTextSize(13);t.setTextColor(0xffb8c3d4);
        t.setPadding(0,dp(5),0,dp(12));dest.addView(t);
    }
    private void button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setOnClickListener(v->action.run());body.addView(b,new LinearLayout.LayoutParams(-1,dp(54)));
    }
    private int dp(int a){return (int)(a*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}