package ir.persiansocks.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

/** App picker with recycled rows and background package enumeration. */
public final class SplitTunnelActivity extends Activity {
    private final Set<String> selected = new HashSet<>();
    private final List<Entry> all = new ArrayList<>(), visible = new ArrayList<>();
    private SplitTunnelPolicy.Mode mode;
    private CheckBox showAll;
    private TextView count;
    private EditText search;
    private Button save;
    private boolean loaded;
    private final android.util.LruCache<String,android.graphics.Bitmap> icons=new android.util.LruCache<String,android.graphics.Bitmap>(1024*1024){protected int sizeOf(String key,android.graphics.Bitmap b){return b.getByteCount();}};
    private final Set<String> iconPending=new HashSet<>();
    private String loadError;
    private final AppsAdapter adapter = new AppsAdapter();
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int ACCENT = Color.rgb(70,226,184);
    private static final class Entry {
        final String name, label;
        final boolean system, updatedSystem;
        Entry(ApplicationInfo app, String label) {
            name=app.packageName; this.label=label;
            system=(app.flags & ApplicationInfo.FLAG_SYSTEM)!=0;
            updatedSystem=(app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
        }
    }
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SplitTunnelPolicy policy=SplitTunnelSettings.load(this);
        selected.addAll(state==null?policy.packages:state.getStringArrayList("selected"));
        mode=state==null?policy.mode:SplitTunnelPolicy.Mode.valueOf(state.getString("mode", "ALL"));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(AppLanguage.direction());
        root.setBackgroundColor(Color.rgb(11,17,29));root.setPadding(dp(16),dp(8),dp(16),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());
                left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(16)+left,dp(8)+top,dp(16)+right,dp(8)+bottom);return insets;
        });
        setContentView(root);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label(AppLanguage.t(R.string.ui_254),20);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button back=styledButton();back.setText(AppLanguage.t(R.string.ui_255));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));content.addView(header);
        content.addView(label(AppLanguage.t(R.string.ui_256),12));
        RadioGroup modes=new RadioGroup(this);
        String[] titles={AppLanguage.t(R.string.ui_257), AppLanguage.t(R.string.ui_258), AppLanguage.t(R.string.ui_259)};
        for(int i=0;i<titles.length;i++){
            RadioButton row=new RadioButton(this);row.setId(100+i);row.setText(AppLanguage.display(titles[i]));row.setTextSize(13);
            modes.addView(row,new RadioGroup.LayoutParams(-1,-2));
        }
        modes.check(100+mode.ordinal());modes.setOnCheckedChangeListener((group,id)->{mode=SplitTunnelPolicy.Mode.values()[id-100];updateCount();});content.addView(modes);
        showAll=new CheckBox(this);showAll.setText(AppLanguage.t(R.string.ui_260));showAll.setTextSize(13);
        showAll.setChecked(state==null?getSharedPreferences("persiansocks",MODE_PRIVATE).getBoolean("split-show-all",false):state.getBoolean("show-all"));
        showAll.setOnCheckedChangeListener((box,on)->filter());content.addView(showAll);
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setHint(AppLanguage.t(R.string.ui_261));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int n,int after){}public void onTextChanged(CharSequence s,int start,int before,int n){filter();}public void afterTextChanged(Editable e){}});
        content.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        count=label(AppLanguage.t(R.string.ui_262),12);tools.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=styledButton();clear.setText(AppLanguage.t(R.string.ui_263));clear.setTextSize(12);
        clear.setOnClickListener(v->{selected.clear();adapter.notifyDataSetChanged();updateCount();});tools.addView(clear,new LinearLayout.LayoutParams(dp(110),dp(44)));content.addView(tools);
        ListView list=new ListView(this);list.setDividerHeight(dp(1));list.addHeaderView(content,null,false);list.setAdapter(adapter);
        list.setOnItemClickListener((parent,row,pos,id)->{int index=pos-list.getHeaderViewsCount();if(index<0||index>=visible.size())return;String name=visible.get(index).name;if(!selected.remove(name))selected.add(name);adapter.notifyDataSetChanged();updateCount();});
        TextView empty=label(AppLanguage.t(R.string.ui_264),14);empty.setGravity(Gravity.CENTER);empty.setVisibility(View.GONE);
        FrameLayout frame=new FrameLayout(this);frame.addView(list,new FrameLayout.LayoutParams(-1,-1));frame.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        save=styledButton();save.setText(AppLanguage.t(R.string.ui_265));save.setEnabled(false);save.setOnClickListener(v->save());root.addView(save,new LinearLayout.LayoutParams(-1,dp(52)));
        if(state!=null)search.setText(state.getString("search", ""));
        worker.execute(()->{
            List<Entry> entries=new ArrayList<>();
            try{
                for(ApplicationInfo app:getPackageManager().getInstalledApplications(0)){
                    if(app.packageName.equals(getPackageName())||!app.enabled)continue;
                    CharSequence label;
                    try{label=getPackageManager().getApplicationLabel(app);}catch(RuntimeException e){label=app.packageName;}
                    entries.add(new Entry(app,label.toString()));
                }
                java.text.Collator collator=java.text.Collator.getInstance(AppLanguage.locale());
                entries.sort((a,b)->{int n=collator.compare(a.label,b.label);return n==0?a.name.compareTo(b.name):n;});
            }catch(RuntimeException e){runOnUiThread(()->{if(!isDestroyed()){loadError=AppLanguage.t(R.string.ui_266);count.setText(loadError);}});return;}
            runOnUiThread(()->{if(isDestroyed()||isFinishing())return;all.addAll(entries);loaded=true;save.setEnabled(true);filter();});
        });
    }
    private void filter(){
        if(search==null||showAll==null)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for(Entry entry:all)if(SplitTunnelPolicy.visible(entry.system,entry.updatedSystem,showAll.isChecked()) &&
            (query.isEmpty()||entry.label.toLowerCase(Locale.ROOT).contains(query)||entry.name.toLowerCase(Locale.ROOT).contains(query)))visible.add(entry);
        adapter.notifyDataSetChanged();updateCount();
    }
    private void updateCount(){
        if(count==null)return;
        if(!loaded){count.setText(loadError==null?AppLanguage.t(R.string.ui_262):loadError);return;}
        int installed=0;for(Entry entry:all)if(selected.contains(entry.name))installed++;
        count.setText(visible.size()+AppLanguage.t(R.string.ui_267)+installed+AppLanguage.t(R.string.ui_268));
    }
    private void save(){
        if(!loaded)return;
        Set<String> existing=new HashSet<>();for(Entry entry:all)if(selected.contains(entry.name))existing.add(entry.name);
        if(mode==SplitTunnelPolicy.Mode.ONLY_SELECTED&&existing.isEmpty()){
            Toast.makeText(this,AppLanguage.t(R.string.ui_269),Toast.LENGTH_LONG).show();return;
        }
        SplitTunnelSettings.save(this,mode,existing,showAll.isChecked());
        Toast.makeText(this,AppLanguage.t(R.string.ui_270),Toast.LENGTH_LONG).show();finish();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putStringArrayList("selected",new ArrayList<>(selected));state.putString("mode",mode.name());
        state.putBoolean("show-all",showAll.isChecked());state.putString("search",search.getText().toString());
    }
    @Override protected void onDestroy(){worker.shutdownNow();icons.evictAll();super.onDestroy();}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(AppLanguage.display(text));v.setTextColor(Color.WHITE);v.setTextSize(size);v.setPadding(0,dp(4),0,dp(4));return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private Button styledButton(){Button b=new Button(this);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(16),0,dp(16),0);b.setBackgroundTintList(null);b.setStateListAnimator(null);android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Color.rgb(23,33,49));bg.setCornerRadius(dp(12));bg.setStroke(dp(1),Color.rgb(48,65,80));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(65,70,226,184)),bg,null));return b;}
    private void loadIcon(String name,ImageView row){if(!iconPending.add(name))return;worker.execute(()->{android.graphics.Bitmap bitmap=null;try{android.graphics.drawable.Drawable source=getPackageManager().getApplicationIcon(name);bitmap=android.graphics.Bitmap.createBitmap(dp(36),dp(36),android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);source.setBounds(0,0,bitmap.getWidth(),bitmap.getHeight());source.draw(canvas);}catch(Exception ignored){}final android.graphics.Bitmap ready=bitmap;runOnUiThread(()->{iconPending.remove(name);if(isDestroyed())return;if(ready!=null){icons.put(name,ready);if(name.equals(row.getTag()))row.setImageBitmap(ready);adapter.notifyDataSetChanged();}});});}
    private final class AppsAdapter extends BaseAdapter {
        public int getCount(){return visible.size();}
        public Object getItem(int pos){return visible.get(pos);}
        public long getItemId(int pos){return pos;}
        public View getView(int pos,View recycled,ViewGroup parent){
            LinearLayout row;
            if(recycled==null){
                row=new LinearLayout(SplitTunnelActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),dp(6),dp(4),dp(6));row.setMinimumHeight(dp(60));
                CheckBox box=new CheckBox(SplitTunnelActivity.this);box.setClickable(false);box.setFocusable(false);box.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));row.addView(box);
                ImageView icon=new ImageView(SplitTunnelActivity.this);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams iconCell=new LinearLayout.LayoutParams(dp(36),dp(36));iconCell.setMarginStart(dp(8));iconCell.setMarginEnd(dp(12));row.addView(icon,iconCell);
                TextView title=label("",14);title.setGravity(AppLanguage.gravity()|Gravity.CENTER_VERTICAL);title.setTextDirection(AppLanguage.textDirection());title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            }else row=(LinearLayout)recycled;
            Entry entry=visible.get(pos);((CheckBox)row.getChildAt(0)).setChecked(selected.contains(entry.name));((TextView)row.getChildAt(2)).setText(entry.label);
            ImageView icon=(ImageView)row.getChildAt(1);icon.setTag(entry.name);android.graphics.Bitmap cached=icons.get(entry.name);if(cached!=null)icon.setImageBitmap(cached);else{icon.setImageDrawable(SiteIcons.placeholder());loadIcon(entry.name,icon);}
            row.setContentDescription(entry.label+(selected.contains(entry.name)?AppLanguage.t(R.string.ui_271):""));return row;
        }
    }
}
