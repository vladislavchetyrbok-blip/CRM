package com.tapmemory.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LinearLayout page,stepsView;
    private Spinner macroSpinner,ipSpinner;
    private EditText repeats,gap,ipUrl;
    private TextView status,summary,target;
    private boolean loading=false;
    private final ArrayList<String> macroIds=new ArrayList<>();
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(status!=null){
                MacroAccessibilityService s=MacroAccessibilityService.instance;
                status.setText(s==null?"Служба выключена. Сначала включите доступ.":s.state);
                status.setTextColor(s==null?0xffffabaf:0xff87e8c5);
            }
            ui.postDelayed(this,600);
        }
    };
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        MacroStore.init(this);
        getWindow().setStatusBarColor(0xff101827);
        getWindow().setNavigationBarColor(0xff101827);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(0xff101827);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(15),dp(16),dp(30));scroll.addView(page);
        setContentView(scroll);
        header("TapMemory 2.2",27);
        info("Записывайте действия в Chrome, изменяйте задержки и воспроизводите их. Все сценарии сохраняются на телефоне.");
        status=label("Проверка службы…",15);page.addView(status);
        button("Разрешить специальные возможности",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        header("1 · Сценарии",19);
        macroSpinner=new Spinner(this);page.addView(macroSpinner);
        macroSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                if(loading||pos<0||pos>=macroIds.size())return;
                saveSettings(); MacroStore.select(MainActivity.this,macroIds.get(pos));refresh();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        action(actions,"＋ Новый",this::newMacro);
        action(actions,"✎ Название",this::renameMacro);
        action(actions,"× Удалить",this::deleteMacro);
        page.addView(actions);
        target=label("",13);target.setTextColor(0xff9bb2c8);
        target.setPadding(0,dp(6),0,0);page.addView(target);
        header("2 · Запись",19);
        info("Нажмите «Новая запись» и откройте Chrome. Нажимайте элементы обычным способом. Плавающая кнопка ■ завершает запись. Клавиатура и пароли не записываются.");
        button("● Новая запись",()->new AlertDialog.Builder(this)
            .setTitle("Начать новую запись?")
            .setMessage("Текущие действия этого сценария будут заменены. Другие сценарии сохранятся.")
            .setPositiveButton("Записать",(d,w)->record(true))
            .setNegativeButton("Отмена",null).show());
        button("＋ Дозаписать действия",()->record(false));
        LinearLayout addActions=new LinearLayout(this);addActions.setOrientation(LinearLayout.HORIZONTAL);
        action(addActions,"Вставить текст",this::clipboardStep);
        action(addActions,"＋ Пауза",this::waitStep);
        page.addView(addActions);
        info("Текст берётся из буфера в момент добавления шага. Перед вставкой должен быть выбран нужный вводимый текстовый элемент.");
        header("3 · Воспроизведение",19);
        repeats=numberField("Повторений: 1, 2, 3… или 0 для бесконечно","1",false);
        gap=numberField("Пауза между повторами, секунды","1.2",true);
        header("IP без отключения Wi-Fi",17);
        String[] modes={"Не менять IP","Через HTTPS API роутера или провайдера"};
        ipSpinner=new Spinner(this);ipSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));
        page.addView(ipSpinner);
        ipUrl=numberField("HTTPS-адрес команды смены IP","",false);
        ipUrl.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        info("Работает с динамическим IP только если роутер/провайдер поддерживает команду переподключения WAN. Wi-Fi телефона не выключается. Перед каждым повтором проверяется, что публичный IP действительно изменился. Без API универсальная смена IP невозможна.");
        repeats.setText(MacroStore.pref(this).getString("repeats","1"));
        gap.setText(MacroStore.pref(this).getString("pause","1.2"));
        ipUrl.setText(MacroStore.pref(this).getString("url",""));
        ipSpinner.setSelection(MacroStore.pref(this).getInt("mode",0));
        button("▶ Запустить сценарий",this::play);
        button("■ Остановить",()->{
            MacroAccessibilityService s=MacroAccessibilityService.instance;
            if(s!=null)s.stopAll();refresh();
        });
        header("4 · Действия",19);
        summary=label("",14);page.addView(summary);
        info("Нажмите «✎» у действия, чтобы точно изменить его паузу в секундах. Стрелки изменяют порядок. Для Chrome используются подписи и идентификаторы элементов, если они доступны.");
        stepsView=new LinearLayout(this);stepsView.setOrientation(LinearLayout.VERTICAL);
        page.addView(stepsView);
        button("Очистить действия текущего сценария",()->new AlertDialog.Builder(this)
            .setTitle("Очистить сценарий?")
            .setMessage("Удаляются только действия этого сценария.")
            .setPositiveButton("Очистить",(d,w)->{MacroStore.save(this,new JSONArray());refresh();})
            .setNegativeButton("Отмена",null).show());
        reloadMacros();refresh();
    }
    private void saveSettings(){
        if(repeats==null||gap==null||ipSpinner==null||ipUrl==null)return;
        MacroStore.pref(this).edit()
            .putString("repeats",repeats.getText().toString())
            .putString("pause",gap.getText().toString())
            .putInt("mode",ipSpinner.getSelectedItemPosition())
            .putString("url",ipUrl.getText().toString().trim()).apply();
    }
    private void reloadMacros(){
        if(macroSpinner==null)return;
        loading=true;
        JSONArray a=MacroStore.macros(this);
        macroIds.clear();ArrayList<String> labels=new ArrayList<>();
        int selected=0;String active=MacroStore.active(this);
        for(int i=0;i<a.length();i++){
            JSONObject j=a.optJSONObject(i);
            if(j==null)continue;
            String id=j.optString("id");
            if(id.equals(active))selected=macroIds.size();
            macroIds.add(id);labels.add(j.optString("name","Сценарий"));
        }
        macroSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        macroSpinner.setSelection(selected);loading=false;
    }
    private void newMacro(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setText("Новый сценарий");
        new AlertDialog.Builder(this).setTitle("Создать сценарий")
            .setView(name).setPositiveButton("Создать",(d,w)->{
                saveSettings();MacroStore.create(this,name.getText().toString());reloadMacros();refresh();
            }).setNegativeButton("Отмена",null).show();
    }
    private void renameMacro(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setText(MacroStore.name(this));
        new AlertDialog.Builder(this).setTitle("Название сценария")
            .setView(name).setPositiveButton("Сохранить",(d,w)->{
                if(!name.getText().toString().trim().isEmpty()){
                    MacroStore.rename(this,name.getText().toString());reloadMacros();
                }
            }).setNegativeButton("Отмена",null).show();
    }
    private void deleteMacro(){
        new AlertDialog.Builder(this).setTitle("Удалить сценарий?")
            .setMessage(MacroStore.name(this))
            .setPositiveButton("Удалить",(d,w)->{
                MacroStore.deleteActive(this);reloadMacros();refresh();
            }).setNegativeButton("Отмена",null).show();
    }
    private void record(boolean clear){
        MacroAccessibilityService s=MacroAccessibilityService.instance;
        if(s==null){toast("Включите службу TapMemory в специальных возможностях.");return;}
        saveSettings();s.startRecording(clear);moveTaskToBack(true);
    }
    private void play(){
        MacroAccessibilityService s=MacroAccessibilityService.instance;
        if(s==null){toast("Включите службу TapMemory.");return;}
        if(MacroStore.steps(this).length()==0){toast("Нет записанных действий.");return;}
        saveSettings();
        if(ipSpinner.getSelectedItemPosition()==1&&
            !ipUrl.getText().toString().trim().startsWith("https://")){
            toast("Нужен HTTPS-адрес API роутера или провайдера.");return;
        }
        s.startPlayback();moveTaskToBack(true);
    }
    private void clipboardStep(){
        ClipboardManager clip=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clip==null||!clip.hasPrimaryClip()){toast("Буфер обмена пуст.");return;}
        ClipData cd=clip.getPrimaryClip();
        if(cd==null||cd.getItemCount()==0){toast("Буфер обмена пуст.");return;}
        CharSequence value=cd.getItemAt(0).coerceToText(this);
        if(value==null||value.length()==0){toast("В буфере нет текста.");return;}
        JSONObject step=MacroStore.step("text",500);
        MacroStore.put(step,"text",value.toString());MacroStore.add(this,step);refresh();
        toast("Шаг вставки текста сохранён.");
    }
    private void waitStep(){
        EditText seconds=new EditText(this);
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        seconds.setText("2");
        new AlertDialog.Builder(this).setTitle("Добавить паузу (секунды)")
            .setView(seconds).setPositiveButton("Добавить",(d,w)->{
                try{
                    long ms=parseMs(seconds.getText().toString());
                    JSONObject step=MacroStore.step("wait",ms);MacroStore.add(this,step);refresh();
                }catch(Exception e){toast("Некорректное число секунд.");}
            }).setNegativeButton("Отмена",null).show();
    }
    private long parseMs(String text){
        double sec=Double.parseDouble(text.trim().replace(',','.'));
        if(!Double.isFinite(sec)||sec<0||sec>3600)throw new IllegalArgumentException("Seconds");
        return Math.round(sec*1000);
    }
    private String describe(JSONObject j){
        String type=j.optString("type");
        String name=j.optString("label");
        if(name.isEmpty())name=j.optString("desc");
        if(name.length()>50)name=name.substring(0,50)+"…";
        switch(type){
            case "tap":return "Нажатие"+(name.isEmpty()?" · "+j.optInt("x")+","+j.optInt("y"):" · "+name);
            case "long":return "Долгое нажатие"+(name.isEmpty()?"":" · "+name);
            case "scroll":return "Прокрутка "+("up".equals(j.optString("direction"))?"вверх":"вниз");
            case "text":
                String t=j.optString("text");
                return "Вставить: "+(t.length()>50?t.substring(0,50)+"…":t);
            case "wait":return "Пауза";
        }
        return type;
    }
    private void refresh(){
        if(stepsView==null)return;
        target.setText("Приложение: "+(MacroStore.target(this).isEmpty()?"определится при записи":MacroStore.target(this)));
        stepsView.removeAllViews();
        JSONArray a=MacroStore.steps(this);long total=0;
        for(int i=0;i<a.length();i++){
            JSONObject step=a.optJSONObject(i);
            if(step!=null)total+=Math.max(0,step.optLong("delay"));
        }
        summary.setText("Шагов: "+a.length()+" · паузы в записи: "+String.format(Locale.ROOT,"%.2f",total/1000.0)+" с");
        if(a.length()==0){infoTo(stepsView,"Здесь появятся записанные касания и прокрутки.");return;}
        for(int i=0;i<a.length();i++){
            JSONObject step=a.optJSONObject(i);if(step==null)continue;
            final int index=i;
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0,dp(5),0,dp(5));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
            TextView title=label((i+1)+". "+describe(step),14);
            title.setMaxLines(2);labels.addView(title);
            TextView timing=label(String.format(Locale.ROOT,"Через %.3f с",step.optLong("delay",0)/1000.0),12);
            timing.setTextColor(0xff8fb0c8);labels.addView(timing);
            row.addView(labels,new LinearLayout.LayoutParams(0,dp(57),1));
            mini(row,"✎",()->editStep(index));
            mini(row,"↑",()->{MacroStore.move(this,index,index-1);refresh();});
            mini(row,"↓",()->{MacroStore.move(this,index,index+1);refresh();});
            mini(row,"×",()->{MacroStore.remove(this,index);refresh();});
            stepsView.addView(row);
            View divider=new View(this);divider.setBackgroundColor(0xff334155);
            stepsView.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    private void editStep(int index){
        JSONArray a=MacroStore.steps(this);JSONObject step=a.optJSONObject(index);
        if(step==null)return;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22),0,dp(22),0);
        TextView text=label("Задержка от предыдущего действия (секунды)",14);
        form.addView(text);
        EditText delay=new EditText(this);
        delay.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        delay.setText(String.format(Locale.ROOT,"%.3f",step.optLong("delay",0)/1000.0));
        form.addView(delay);
        EditText contents=null;
        if("text".equals(step.optString("type"))){
            TextView t=label("Текст для вставки",14);form.addView(t);
            contents=new EditText(this);contents.setText(step.optString("text"));form.addView(contents);
        }
        final EditText inputText=contents;
        new AlertDialog.Builder(this).setTitle("Изменить действие "+(index+1))
            .setView(form).setPositiveButton("Сохранить",(d,w)->{
                try{
                    long ms=parseMs(delay.getText().toString());
                    MacroStore.put(step,"delay",ms);
                    if(inputText!=null)MacroStore.put(step,"text",inputText.getText().toString());
                    MacroStore.replace(this,index,step);refresh();
                }catch(Exception e){toast("Некорректная задержка.");}
            }).setNegativeButton("Отмена",null).show();
    }
    private TextView label(String content,int size){
        TextView v=new TextView(this);v.setText(content);v.setTextSize(size);
        v.setTextColor(Color.WHITE);return v;
    }
    private void header(String content,int size){
        TextView v=label(content,size);
        v.setPadding(0,dp(16),0,dp(8));page.addView(v);
    }
    private void info(String content){infoTo(page,content);}
    private void infoTo(LinearLayout parent,String content){
        TextView v=label(content,13);v.setTextColor(0xffacbed0);
        v.setPadding(0,dp(4),0,dp(11));parent.addView(v);
    }
    private void button(String title,Runnable runnable){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setOnClickListener(v->runnable.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(51));
        p.setMargins(0,dp(3),0,dp(3));page.addView(b,p);
    }
    private void action(LinearLayout parent,String name,Runnable runnable){
        Button b=new Button(this);b.setText(name);b.setTextSize(12);b.setAllCaps(false);
        b.setMinWidth(0);b.setPadding(0,0,0,0);
        b.setOnClickListener(v->runnable.run());
        parent.addView(b,new LinearLayout.LayoutParams(0,dp(45),1));
    }
    private void mini(LinearLayout parent,String text,Runnable runnable){
        Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);
        b.setOnClickListener(v->runnable.run());
        parent.addView(b,new LinearLayout.LayoutParams(dp(36),dp(44)));
    }
    private EditText numberField(String hint,String fallback,boolean decimal){
        EditText field=new EditText(this);field.setSingleLine(true);
        field.setHint(hint);field.setHintTextColor(0xffa1aec0);field.setTextColor(Color.WHITE);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_NUMBER_FLAG_SIGNED));
        field.setText(fallback);
        page.addView(field,new LinearLayout.LayoutParams(-1,dp(54)));return field;
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){
        super.onResume();if(stepsView!=null)refresh();
        ui.removeCallbacks(ticker);ui.post(ticker);
    }
    @Override protected void onPause(){
        saveSettings();ui.removeCallbacks(ticker);super.onPause();
    }
    @Override protected void onDestroy(){ui.removeCallbacks(ticker);super.onDestroy();}
}