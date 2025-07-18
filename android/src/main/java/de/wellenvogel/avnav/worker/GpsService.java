package de.wellenvogel.avnav.worker;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import de.wellenvogel.avnav.appapi.AddonHandler;
import de.wellenvogel.avnav.appapi.ExtendedWebResourceResponse;
import de.wellenvogel.avnav.appapi.INavRequestHandler;
import de.wellenvogel.avnav.appapi.PostVars;
import de.wellenvogel.avnav.appapi.RequestHandler;
import de.wellenvogel.avnav.appapi.WebServer;
import de.wellenvogel.avnav.charts.ChartHandler;
import de.wellenvogel.avnav.main.BuildConfig;
import de.wellenvogel.avnav.main.Constants;
import de.wellenvogel.avnav.main.Dummy;
import de.wellenvogel.avnav.main.IMediaUpdater;
import de.wellenvogel.avnav.main.R;
import de.wellenvogel.avnav.mdns.MdnsWorker;
import de.wellenvogel.avnav.mdns.Resolver;
import de.wellenvogel.avnav.mdns.Target;
import de.wellenvogel.avnav.settings.AudioEditTextPreference;
import de.wellenvogel.avnav.settings.SettingsActivity;
import de.wellenvogel.avnav.util.AvnLog;
import de.wellenvogel.avnav.util.AvnUtil;
import de.wellenvogel.avnav.util.NmeaQueue;

import static de.wellenvogel.avnav.main.Constants.LOGPRFX;

/**
 * Created by andreas on 12.12.14.
 */
public class GpsService extends Service implements RouteHandler.UpdateReceiver, INavRequestHandler {


    private static final String CHANNEL_ID = "main" ;
    private static final String CHANNEL_ID_NEW = "main_new" ;


    private Context ctx;

    private final GpsServiceBinder mBinder = new GpsServiceBinder();
    private boolean isRunning;  //this is our view whether we are running or not
                                //running means that we are registered for updates and have our timer active

    //properties
    private Handler handler = new Handler();
    private long timerSequence=1;
    private Runnable runnable;
    private IMediaUpdater mediaUpdater;
    private static final int NOTIFY_ID=Constants.LOCALNOTIFY;
    private HashMap<String,Alarm> alarmStatus=new HashMap<String, Alarm>();
    private MediaPlayer mediaPlayer=null;
    private int mediaRepeatCount=0;
    private boolean gpsLostAlarmed=false;
    private boolean mobAlarm=false;
    private BroadcastReceiver broadCastReceiver;
    private BroadcastReceiver triggerReceiver; //trigger rescans...
    PendingIntent watchdogIntent=null;
    private static final String WATCHDOGACTION="restart";
    private RequestHandler requestHandler;
    private RouteHandler.RoutePoint lastAlarmWp=null;
    private final NmeaQueue queue=new NmeaQueue();
    long timerInterval =1000;
    Alarm lastNotifiedAlarm=null;
    boolean notificationSend=false;
    private long alarmSequence=System.currentTimeMillis();
    private final ArrayList<IWorker> workers =new ArrayList<>();
    private final ArrayList<IWorker> internalWorkers=new ArrayList<>();
    private static final int MIN_WORKER_ID=10;
    private int workerId=MIN_WORKER_ID; //1-9 reserverd for fixed workers like decoder,...
    private final HashMap<String, Resolver> mdnsResolvers=new HashMap<>();
    private final ArrayList<InetAddress> interfaceAddresses=new ArrayList<>();
    private HashSet<NsdManager.DiscoveryListener> discoveryListeners=new HashSet<>();
    private NsdManager nsdManager;
    private final HashSet<NsdServiceInfo> services=new HashSet<>();
    private final HashMap<Integer,Registration> registeredServices= new HashMap<>();
    private long configSequence=System.currentTimeMillis();
    private final Object configSequenceLock=new Object();
    private int avnavVersion=0;
    private boolean allowAllPlugins=true;

    public void onResumeInternal() {
        AvnLog.i(LOGPRFX,"onResumeInternal");
        onResumeWorkers();
        AvnLog.i(LOGPRFX,"onResumeInternal done");
    }

    private static class Registration{
        NsdManager.RegistrationListener listener;
        NsdServiceInfo info;
        Registration(NsdServiceInfo info, NsdManager.RegistrationListener listener){
            this.listener=listener;
            this.info=info;
        }
    }
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {

            if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null) {
                    for (IWorker w : workers) {
                        if (w instanceof UsbConnectionHandler){
                            ((UsbConnectionHandler)w).deviceDetach(dev);
                        }
                    }
                }
            }
        }
    };
    boolean receiverRegistered=false;


    private static final String LOGPRFX="Avnav:GpsService";
    private BroadcastReceiver broadCastReceiverStop;
    private BroadcastReceiver broadCastReceiverPlugin;
    private boolean mdnsUpdateRunning;

    @Override
    public void updated() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    timerAction();
                } catch (JSONException e) {
                    AvnLog.e("error in timer action",e);
                }
            }
        });
        Intent bc=new Intent(Constants.BC_RELOAD_DATA);
        bc.setPackage(getPackageName());
        sendBroadcast(bc);

    }

    @Override
    public ExtendedWebResourceResponse handleDownload(String name, Uri uri) throws Exception {
        return null;
    }

    @Override
    public boolean handleUpload(PostVars postData, String name, boolean ignoreExisting) throws Exception {
        return false;
    }

    @Override
    public JSONArray handleList(Uri uri, RequestHandler.ServerInfo serverInfo) throws Exception {
        return null;
    }

    @Override
    public boolean handleDelete(String name, Uri uri) throws Exception {
        return false;
    }

    private void updateConfigSequence(){
        synchronized (configSequenceLock){
            configSequence++;
        }
    }

    @Override
    public JSONObject handleApiRequest(Uri uri, PostVars postData, RequestHandler.ServerInfo serverInfo) throws Exception {
        if (serverInfo != null){
            return RequestHandler.getErrorReturn("can only handle config locally");
        }
        String command=AvnUtil.getMandatoryParameter(uri,"command");
        if ("createHandler".equals(command)){
            String typeName=AvnUtil.getMandatoryParameter(uri,"handlerName");
            String config=postData.getAsString();
            int newId=addWorker(typeName,new JSONObject(config));
            updateConfigSequence();
            return RequestHandler.getReturn(new AvnUtil.KeyValue<Integer>("id",newId));
        }
        if ("getAddables".equals(command)){
            List<String> names=WorkerFactory.getInstance().getKnownTypes(true,this);
            JSONArray data=new JSONArray(names);
            return RequestHandler.getReturn(new AvnUtil.KeyValue<JSONArray>("data",data));
        }
        if ("getAddAttributes".equals(command)){
            String typeName=AvnUtil.getMandatoryParameter(uri,"handlerName");
            try{
                IWorker w=WorkerFactory.getInstance().createWorker(typeName,this,null);
                return RequestHandler.getReturn(
                        new AvnUtil.KeyValue<JSONArray>("data",w.getParameterDescriptions(this))
                );
            }catch (WorkerFactory.WorkerNotFound e){
                return RequestHandler.getErrorReturn("no handler of type "+typeName+" found");
            }
        }
        if ("canRestart".equals(command)){
            return RequestHandler.getReturn(new AvnUtil.KeyValue<Boolean>("canRestart",false));
        }
        int id=Integer.parseInt(AvnUtil.getMandatoryParameter(uri,"handlerId"));
        IWorker worker=findWorkerById(id);
        if (worker == null){
            return RequestHandler.getErrorReturn("worker with id "+id+" not found");
        }
        if ("getEditables".equals(command)){
            JSONObject rt=worker.getEditableParameters(true,this);
            rt.put("status","OK");
            return rt;
        }
        if ("setConfig".equals(command)){
            String config=postData.getAsString();
            updateWorkerConfig(worker,new JSONObject(config));
            updateConfigSequence();
            return RequestHandler.getReturn();
        }
        if ("deleteHandler".equals(command)){
            if (! worker.getStatus().canDelete){
                return RequestHandler.getErrorReturn("handler "+id+" cannot be deleted");
            }
            deleteWorker(worker);
            updateConfigSequence();
            return RequestHandler.getReturn();
        }
        return RequestHandler.getErrorReturn("invalid command "+command);
    }

    @Override
    public ExtendedWebResourceResponse handleDirectRequest(Uri uri, RequestHandler handler, String method) throws Exception {
        return null;
    }

    @Override
    public String getPrefix() {
        return null;
    }

    public RequestHandler getRequestHandler() {
        return requestHandler;
    }

    public static interface MainActivityActions{
        void showSettings(boolean checkInitially);
        void showPermissionRequest(String[]permissions, boolean exitOnCancel);
        void mainGoBack();
        void mainShutdown();

        /**
         * a callback that is executed at the end of onStartCommand if the service is already bound at this point in time
         * this way the activity will be able to execute some action if the service is ready even if a (potentially stopped) service was already bound
         * before the startService was executed
         */
        void mainServiceBound();
    }
    public class GpsServiceBinder extends Binder{
        MainActivityActions mainCallback;
        public GpsService getService(){
            return GpsService.this;
        }
        synchronized public void registerCallback(MainActivityActions cb){
            mainCallback=cb;
        }
        synchronized public void deregisterCallback(){
            mainCallback=null;
        }
        synchronized MainActivityActions getCallback(){
            return mainCallback;
        }
    };

    @Override
    public IBinder onBind(Intent arg0)
    {
        AvnLog.i(LOGPRFX,"Service bind called");
        return mBinder;
    }


    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID_NEW, name, importance);
            channel.setDescription(description);
            channel.setSound(null,null);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            try{
                //we need a new channel as we did not disable the sound initially - but we cannot change an existing one
                notificationManager.deleteNotificationChannel(CHANNEL_ID);
            }catch (Throwable t){}
        }
    }

    private boolean handleNotification(boolean start, boolean startForeground, int fgType){
        if (start) {
            Alarm currentAlarm=getCurrentAlarm();
            if (! notificationSend || (currentAlarm != null && ! currentAlarm.equals(lastNotifiedAlarm))|| (currentAlarm == null && lastNotifiedAlarm != null))
            {
                createNotificationChannel();
                Intent notificationIntent = new Intent(this, Dummy.class);
                PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                        notificationIntent, AvnUtil.buildPiFlags(PendingIntent.FLAG_UPDATE_CURRENT,true));
                Intent broadcastIntent = new Intent();
                broadcastIntent.setAction(Constants.BC_STOPALARM);
                PendingIntent stopAlarmPi = PendingIntent.getBroadcast(ctx, 1, broadcastIntent, AvnUtil.buildPiFlags(PendingIntent.FLAG_CANCEL_CURRENT,true));
                Intent broadcastIntentStop = new Intent();
                broadcastIntentStop.setAction(Constants.BC_STOPAPPL);
                PendingIntent stopAppl = PendingIntent.getBroadcast(ctx, 1, broadcastIntentStop, AvnUtil.buildPiFlags(PendingIntent.FLAG_CANCEL_CURRENT,true));
                NotificationCompat.Builder notificationBuilder =
                        new NotificationCompat.Builder(this, CHANNEL_ID_NEW);
                notificationBuilder.setSmallIcon(R.drawable.sailboat);
                notificationBuilder.setContentTitle(getString(R.string.notifyTitle));
                if (currentAlarm == null) {
                    notificationBuilder.setContentText(getString(R.string.notifyText));
                } else {
                    notificationBuilder.setContentText(currentAlarm.name + " Alarm");
                }
                notificationBuilder.setContentIntent(contentIntent);
                notificationBuilder.setOngoing(true);
                notificationBuilder.setAutoCancel(false);
                notificationBuilder.addAction(0,getString(R.string.nfexit),stopAppl);
                if (currentAlarm != null){
                    notificationBuilder.addAction(0,getString(R.string.nfalarm),stopAlarmPi);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                }
                NotificationManager mNotificationManager =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (startForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFY_ID,notificationBuilder.build(), fgType);
                    }
                    else {
                        startForeground(NOTIFY_ID, notificationBuilder.build());
                    }
                } else {
                    mNotificationManager.notify(NOTIFY_ID, notificationBuilder.build());
                }
                lastNotifiedAlarm=currentAlarm;
                notificationSend=true;
            }

        }
        else{
            notificationSend=false;
            lastNotifiedAlarm=null;
            NotificationManager mNotificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.cancel(NOTIFY_ID);

        }
        return true;
    }

    private abstract static class WorkerConfig{
        int id;
        String configName;
        String typeName;
        WorkerConfig(String typeName,int id){
            this.id=id;
            this.configName="internal."+typeName;
            this.typeName=typeName;
        }
        WorkerConfig(String typeName,int id, String configName){
            this.id=id;
            this.configName="internal."+configName;
            this.typeName=typeName;
        }
        abstract IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException;
    }

    private static final WorkerConfig WDECODER= new WorkerConfig("Decoder", 1) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) {
            return new Decoder(typeName,ctx,queue);
        }
    };
    private static final WorkerConfig WROUTER=new WorkerConfig("Router",2){
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            SharedPreferences prefs=ctx.getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
            File routeDir=new File(AvnUtil.getWorkDir(prefs,ctx),"routes");
            RouteHandler rt=new RouteHandler(routeDir,ctx,queue);
            rt.setMediaUpdater(ctx.getMediaUpdater());
            return rt;
        }
    };
    private static final WorkerConfig WTRACK=new WorkerConfig("Track",3){
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            SharedPreferences prefs=ctx.getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
            File newTrackDir=new File(AvnUtil.getWorkDir(prefs,ctx),"tracks");
            TrackWriter rt=new TrackWriter(newTrackDir,ctx);
            return rt;
        }
    };
    private static final WorkerConfig WLOGGER= new WorkerConfig("Logger", 4) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            SharedPreferences prefs=ctx.getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
            File newTrackDir=new File(AvnUtil.getWorkDir(prefs,ctx),"tracks");
            return new NmeaLogger(newTrackDir,ctx,queue,null);
        }
    };
    private static final WorkerConfig WSERVER= new WorkerConfig("WebServer",5) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            return new WebServer(ctx);
        }
    };
    private static final WorkerConfig WGPS= new WorkerConfig("InternalGPS", 6) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) {
            return new AndroidPositionHandler(typeName,ctx,queue);
        }
    };
    private static final WorkerConfig WMDNS=new WorkerConfig("MDNSresolver",7) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            return new MdnsWorker(typeName,ctx);
        }
    };
    private static final WorkerConfig WREMOTE=new WorkerConfig("RemoteChannel",8) {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            return new RemoteChannel(typeName,ctx);
        }
    };
    private static final WorkerConfig WOCHARTS=new WorkerConfig(PluginWorker.TYPENAME,9,PluginWorker.TYPENAME+".ocharts") {
        @Override
        IWorker createWorker(GpsService ctx, NmeaQueue queue) throws IOException {
            String suffix=BuildConfig.BUILD_TYPE;
            if (suffix.equals("release")){
                suffix="";
            }
            else{
                suffix="."+suffix;
            }
            return new PluginWorker(ctx,"ocharts","de.wellenvogel.ochartsprovider"+ suffix,"de.wellenvogel.ochartsprovider.OchartsService");
        }
    };

    private static final WorkerConfig[] INTERNAL_WORKERS ={WDECODER,WROUTER,WTRACK,WLOGGER,WSERVER,WGPS,WMDNS,WREMOTE ,WOCHARTS};

    private synchronized int getNextWorkerId(){
        workerId++;
        return workerId;
    }
    private synchronized IWorker findWorkerById(int id){
        if (id >= MIN_WORKER_ID) {
            for (IWorker w : workers) {
                if (w.getId() == id) return w;
            }
        }
        else {
            for (IWorker w : internalWorkers) {
                if (w.getId() == id) return w;
            }
        }
        return null;
    }
    public static JSONArray getWorkerConfig(Context ctx) throws JSONException {
        SharedPreferences prefs=ctx.getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
        String config=prefs.getString(Constants.HANDLER_CONFIG,null);
        JSONArray rt=null;
        if (config != null){
            try{
                rt=new JSONArray(config);
            }catch (Throwable t){
                AvnLog.e("unable to parse Handler config "+config,t);
            }
        }
        if (rt != null) {
            return rt;
        }
        rt=new JSONArray();
        return rt;
    }

    public static NeededPermissions getNeededPermissions(Context ctx){
        NeededPermissions rt = new NeededPermissions();
        try {
            JSONArray handlerConfig = getWorkerConfig(ctx);
            for (int i = 0; i < handlerConfig.length(); i++) {
                JSONObject config = handlerConfig.getJSONObject(i);
                if (WorkerFactory.BLUETOOTH_NAME.equals(Worker.typeFromConfig(config))
                        && Worker.ENABLED_PARAMETER.fromJson(config)) {
                    rt.bluetooth = true;
                    break;
                }
            }
        }catch (Exception e){
            AvnLog.e("unable to query worker config for permissions",e);
        }
        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFNAME, Context.MODE_PRIVATE);
        String gpsConfig=prefs.getString(WGPS.configName,null);
        if (gpsConfig == null){
            rt.gps=true;
        }
        else{
            JSONObject o= null;
            try {
                o = new JSONObject(gpsConfig);
                rt.gps=Worker.ENABLED_PARAMETER.fromJson(o);
            } catch (JSONException e) {
                rt.gps=true;
            }
        }
        return rt;
    }
    private void handleMigration(){
        try {
            SharedPreferences prefs = getSharedPreferences(Constants.PREFNAME, Context.MODE_PRIVATE);
            String config = prefs.getString(Constants.HANDLER_CONFIG, null);
            if (config != null) return;
            AvnLog.i("running config migration");
            SharedPreferences.Editor edit=prefs.edit();
            int webServerPort = -1;
            if (Constants.MODE_SERVER.equals(prefs.getString(Constants.RUNMODE, null))) {
                webServerPort = Integer.parseInt(prefs.getString(Constants.WEBSERVERPORT, "34567"));
            }

            String ip = null;
            int port = -1;
            boolean ipais = false;
            boolean ipnmea = false;
            String ipFilter = "";
            ipais = prefs.getBoolean(Constants.IPAIS, false);
            ipnmea = prefs.getBoolean(Constants.IPNMEA, false);
            if (ipais || ipnmea) {
                ip = prefs.getString(Constants.IPADDR, null);
                port = Integer.parseInt(prefs.getString(Constants.IPPORT, "-1"));
            }
            if (!ipais) {
                ipFilter = "$";
            }
            if (!ipnmea) {
                ipFilter = "!";
            }
            //step1: internal receiver
            if (ipais && ipnmea){
                //internal gps disabled
                JSONObject internal=new JSONObject();
                Worker.ENABLED_PARAMETER.write(internal,false);
                edit.putString(WGPS.typeName,internal.toString());
            }
            JSONArray newConfig = new JSONArray();
            JSONObject handler=null;
            //if we need ip
            if (ip != null && port >= 0) {
                handler = new JSONObject();
                Worker.TYPENAME_PARAMETER.write(handler,WorkerFactory.SOCKETREADER_NAME);
                Worker.IPADDRESS_PARAMETER.write(handler,ip);
                Worker.IPPORT_PARAMETER.write(handler,port);
                if (ipFilter != null) Worker.FILTER_PARAM.write(handler,ipFilter);
                newConfig.put(handler);
                AvnLog.i("adding ip connection to "+ip+":"+port);
            }
            edit.putString(Constants.HANDLER_CONFIG,newConfig.toString());
            if (webServerPort > 0){
                boolean externalAccess=prefs.getBoolean(Constants.EXTERNALACCESS,false);
                handler=new JSONObject();
                WebServer.ENABLED_PARAMETER.write(handler,true);
                WebServer.ANY_ADDRESS.write(handler,externalAccess);
                WebServer.PORT.write(handler,webServerPort);
                edit.putString(WSERVER.configName,handler.toString());
                AvnLog.i("adding webserver, port "+webServerPort);
            }
            String MMSI=prefs.getString(Constants.AISOWN,null);
            if (MMSI != null){
                handler=new JSONObject();
                Decoder.OWN_MMSI.write(handler,MMSI);
                edit.putString(WDECODER.configName,handler.toString());
            }
            edit.commit();
        } catch (Throwable t) {
        }
    }

    public static void resetWorkerConfig(Context ctx){
        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFNAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit=prefs.edit();
        edit.putString(Constants.HANDLER_CONFIG,(new JSONArray()).toString());
        for (WorkerConfig iw:INTERNAL_WORKERS){
            edit.putString(iw.configName,null);
        }
        edit.commit();
    }

    private Decoder getDecoder(){
        IWorker decoder=findWorkerById(WDECODER.id);
        return (Decoder)decoder;
    }
    public RouteHandler getRouteHandler(){
        IWorker handler=findWorkerById(WROUTER.id);
        return (RouteHandler)handler;
    }
    public TrackWriter getTrackWriter(){
        IWorker writer=findWorkerById(WTRACK.id);
        return (TrackWriter)writer;
    }
    public NmeaLogger getNmeaLogger(){
        IWorker logger=findWorkerById(WLOGGER.id);
        return (NmeaLogger)logger;
    }
    public WebServer getWebServer() {
        IWorker server=findWorkerById(WSERVER.id);
        return (WebServer)server;
    }
    public MdnsWorker getMdnsResolver(){
        IWorker mdns=findWorkerById(WMDNS.id);
        return (MdnsWorker) mdns;
    }

    public RemoteChannel getRemoteChannel(){
        IWorker rc=findWorkerById(WREMOTE.id);
        return (RemoteChannel) rc;
    }
    public ChartHandler getChartHandler(){
        RequestHandler r=getRequestHandler();
        if (r == null) return null;
        return r.getChartHandler();
    }

    public AddonHandler getAddonHandler(){
        RequestHandler r=getRequestHandler();
        if (r == null) return null;
        return r.getAddonHandler();
    }

    /**
     * must be called when already protected by synchronized
     * @param worker
     * @throws JSONException
     */
    private void saveWorkerConfig(IWorker worker) throws JSONException {
        SharedPreferences prefs = getSharedPreferences(Constants.PREFNAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit=prefs.edit();
        if (worker == null || worker.getId() >= MIN_WORKER_ID) {
            JSONArray newConfig = new JSONArray();
            for (IWorker w : workers) {
                JSONObject wc = w.getConfig();
                wc.put(Worker.TYPENAME_PARAMETER.name, w.getTypeName());
                newConfig.put(wc);
            }
            edit.putString(Constants.HANDLER_CONFIG, newConfig.toString());
        }
        else if (worker != null){
            for (WorkerConfig cfg: INTERNAL_WORKERS){
                if (cfg.id == worker.getId()){
                    JSONObject jo=worker.getConfig();
                    edit.putString(cfg.configName,jo.toString());
                    break;
                }
            }
        }
        edit.commit();
    }
    private synchronized void updateWorkerConfig(IWorker worker, JSONObject newConfig) throws JSONException, IOException {
        JSONObject oldConfig=worker.getConfig();
        worker.setParameters(newConfig, false,true);
        EditableParameter.IntegerParameter prioParam=(EditableParameter.IntegerParameter)worker.getParameter(Worker.SOURCE_PRIORITY_PARAMETER,true);
        int oldPrio=prioParam.fromJson(oldConfig);
        int newPrio=prioParam.fromJson(worker.getConfig());
        if (oldPrio != newPrio){
            AvnLog.i(LOGPRFX,"source priority changed for "+worker.getId()+", reset data");
            Decoder decoder=getDecoder();
            if (decoder != null){
                decoder.cleanup();
            }
        }
        worker.start(new IWorker.PermissionCallback() {
            @Override
            public void permissionNeeded(NeededPermissions perm) {
                MainActivityActions actions=getMainActions();
                if (actions == null) return;
                if (perm.bluetooth){
                    if(!SettingsActivity.checkBluetooth(GpsService.this)){
                        if (Build.VERSION.SDK_INT >= 31) {
                            actions.showPermissionRequest(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, false);
                        }
                    }
                }
                if (perm.gps){
                    if (!SettingsActivity.checkGpsPermission(GpsService.this)){
                        actions.showPermissionRequest(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, false);
                    }
                }
            }
        }); //will restart
        saveWorkerConfig(worker);
    }
    private synchronized int addWorker(String typeName, JSONObject newConfig) throws WorkerFactory.WorkerNotFound, JSONException, IOException {
        IWorker newWorker = WorkerFactory.getInstance().createWorker(typeName, this, queue);
        return addWorker(newWorker,newConfig);
    }
    private synchronized int addWorker(IWorker newWorker, JSONObject newConfig) throws IOException, JSONException {
        newWorker.setId(getNextWorkerId());
        String typeName=newWorker.getTypeName();
        newWorker.setParameters(newConfig, true,true);
        newWorker.start(null);
        String currentType=null;
        boolean inserted=false;
        for (int i=0;i<workers.size();i++){
            if (typeName.equals(currentType) && !workers.get(i).getTypeName().equals(typeName)){
                inserted=true;
                workers.add(i,newWorker);
                break;
            }
            currentType=workers.get(i).getTypeName();
        }
        if (! inserted){
            workers.add(newWorker);
        }
        saveWorkerConfig(newWorker);
        return newWorker.getId();
    }
    private synchronized void deleteWorker(IWorker worker) throws JSONException {
        worker.stopAndWait();
        int workerId=-1;
        for (int i=0;i<workers.size();i++){
            if (worker.getId() == workers.get(i).getId()){
                workerId=i;
                break;
            }
        }
        if (workerId >= 0){
            workers.remove(workerId);
        }
        saveWorkerConfig(worker);
    }
    private synchronized void stopWorkers(boolean wait){
        AvnLog.i(LOGPRFX,"stop workers");
        for (IWorker w: workers){
            try{
                if (wait) w.stopAndWait();
                else w.stop();
            }catch (Throwable t){
                AvnLog.e("unable to stop worker "+w.getStatus().toString());
            }
        }
        workers.clear();
        for (IWorker w: internalWorkers){
            try{
                w.stop();
            }catch (Throwable t){
                AvnLog.e("unable to stop worker "+w.getStatus().toString());
            }
        }
        internalWorkers.clear();
    }

    private synchronized void onResumeWorkers(){
        for (IWorker w: workers){
            try{
                w.onResume();
            }catch (Throwable t){
                AvnLog.e("unable to stop worker "+w.getStatus().toString());
            }
        }
        for (IWorker w: internalWorkers){
            try{
                w.onResume();
            }catch (Throwable t){
                AvnLog.e("unable to stop worker "+w.getStatus().toString());
            }
        }
    }

    public static boolean handlesUsbDevice(Context ctx,String deviceName){
        try {
            JSONArray workers = getWorkerConfig(ctx);
            for (int i = 0; i < workers.length(); i++) {
                JSONObject wc = workers.getJSONObject(i);
                try {
                    if (WorkerFactory.USB_NAME.equals(Worker.TYPENAME_PARAMETER.fromJson(wc))) {
                        if (UsbConnectionHandler.DEVICE_SELECT.fromJson(wc).equals(deviceName)) {
                            return true;
                        }
                    }
                }
                catch (Throwable t){}
            }
        }catch (Throwable t){}
        return false;
    }

    private synchronized void handleStartup(boolean isWatchdog){
        SharedPreferences prefs=getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
        AvnLog.i(LOGPRFX,"service handleStartup, watchdog="+isWatchdog);
        avnavVersion=prefs.getInt(Constants.VERSION,0);
        allowAllPlugins=prefs.getBoolean(Constants.ALLOW_PLUGINS,true);
        if (! isWatchdog || requestHandler == null){
            if (requestHandler != null) requestHandler.stop();
            requestHandler=new RequestHandler(this);
        }
        if (! isWatchdog || internalWorkers.size() == 0) {
            for (WorkerConfig cfg : INTERNAL_WORKERS){
                try {
                    IWorker worker=cfg.createWorker(this,queue);
                    worker.setId(cfg.id);
                    String parameters=prefs.getString(cfg.configName,null);
                    if (parameters != null){
                        try{
                            JSONObject po=new JSONObject(parameters);
                            worker.setParameters(po,true,false);
                        }catch (JSONException e){
                            //all internal workers must be able to run with empty parameters
                            AvnLog.e("error parsing decoder parameters",e);
                        }
                    }
                    worker.start(null);
                    internalWorkers.add(worker);
                } catch (Throwable t) {
                    AvnLog.e("unable to create worker "+cfg.typeName,t);
                    Toast.makeText(this,"Unable to create "+cfg.typeName+": "+t.getMessage(),Toast.LENGTH_LONG).show();
                }

            }
        }
        if (! isWatchdog || workers.size() == 0) {
            workerId=MIN_WORKER_ID;
            try {
                JSONArray handlerConfig = getWorkerConfig(this);
                for (int i = 0; i < handlerConfig.length(); i++) {
                    try {
                        JSONObject config = handlerConfig.getJSONObject(i);
                        IWorker worker = WorkerFactory.getInstance().createWorker(
                                Worker.TYPENAME_PARAMETER.fromJson(config), this, queue);
                        worker.setId(getNextWorkerId());
                        try {
                            worker.setParameters(config, true,false);
                            worker.start(null);
                        }catch (Throwable t){
                            worker.setStatus(WorkerStatus.Status.ERROR,"unable to set parameters: "+t.getMessage());
                        }
                        workers.add(worker);
                    } catch (Throwable t) {
                        AvnLog.e("unable to create handler " + i, t);
                    }
                }
            } catch (Throwable t) {
                AvnLog.e("unable to create channels", t);
            }
        }
        AvnLog.i(LOGPRFX,"Service handleStartup done");
        isRunning=true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent,flags,startId);
        boolean isWatchdog=false;
        if (intent != null && intent.getAction() != null && intent.getAction().equals(WATCHDOGACTION)) isWatchdog=true;
        AvnLog.i(LOGPRFX,"service onStartCommand, watchdog="+isWatchdog+", running="+isRunning);
        if (! isWatchdog && isRunning) return Service.START_REDELIVER_INTENT;
        if (isWatchdog && ! isRunning) return Service.START_NOT_STICKY;
        int fgType=0;
        if (intent != null){
            fgType=intent.getIntExtra(Constants.SERVICE_TYPE,0);
        }
        if (! handleNotification(true,true, fgType)){
            stopMe();
            return Service.START_NOT_STICKY;
        }
        if (! isWatchdog) {
            stopWorkers(true);
            handleMigration();
        }
        if (discoveryListeners.size() == 0) {
            startDiscovery();
        }
        handleStartup(isWatchdog);
        if (! isWatchdog || runnable == null) {
            timerSequence++;
            runnable = new TimerRunnable(timerSequence);
            handler.postDelayed(runnable, timerInterval);
        }
        if (! receiverRegistered) {
            registerReceiver(usbReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
            receiverRegistered=true;
        }
        if (! isWatchdog) {
            MainActivityActions cb = mBinder.getCallback();
            if (cb != null) cb.mainServiceBound();
        }
        return Service.START_REDELIVER_INTENT;
    }

    private void stopDiscovery(){
        if (nsdManager == null){
            discoveryListeners.clear();
            return;
        }
        for (NsdManager.DiscoveryListener l:discoveryListeners){
            nsdManager.stopServiceDiscovery(l);
        }
        discoveryListeners.clear();
    }

    private void startDiscovery() {
        if (nsdManager == null) {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        }
        if (nsdManager != null) {
            for (TcpServiceReader.Description d : TcpServiceReader.SERVICES) {
                NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
                    @Override
                    public void onStartDiscoveryFailed(String serviceType, int errorCode) {

                    }

                    @Override
                    public void onStopDiscoveryFailed(String serviceType, int errorCode) {

                    }

                    @Override
                    public void onDiscoveryStarted(String serviceType) {

                    }

                    @Override
                    public void onDiscoveryStopped(String serviceType) {

                    }

                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        synchronized (services) {
                            ArrayList<NsdServiceInfo> toRemove=new ArrayList<>();
                            for (NsdServiceInfo si:services){
                                if (si.getServiceType().equals(serviceInfo.getServiceType()) &&
                                    si.getServiceName().equals(serviceInfo.getServiceName())){
                                    toRemove.add(si);
                                }
                            }
                            for (NsdServiceInfo si:toRemove){
                                services.remove(si);
                            }
                            services.add(serviceInfo);
                        }

                    }

                    @Override
                    public void onServiceLost(NsdServiceInfo serviceInfo) {
                        synchronized (services) {
                            ArrayList<NsdServiceInfo> toRemove=new ArrayList<>();
                            for (NsdServiceInfo si:services){
                                if (si.getServiceType().equals(serviceInfo.getServiceType()) &&
                                        si.getServiceName().equals(serviceInfo.getServiceName())){
                                    toRemove.add(si);
                                }
                            }
                            for (NsdServiceInfo si:toRemove){
                                services.remove(si);
                            }
                        }
                    }
                };
                discoveryListeners.add(discoveryListener);
                nsdManager.discoverServices(d.serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            }
        }
    }

    public boolean resolveService(String name, String type, Target.Callback callback){
        NsdServiceInfo service=null;
        synchronized (services) {
            for (NsdServiceInfo info : services) {
                if (type.equals(info.getServiceType()) && name.equals(info.getServiceName())) {
                    service = info;
                    break;
                }
            }
        }
        if (service == null) return false;
        if (nsdManager == null) return false;
        nsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                Target.ServiceTarget res = new Target.ServiceTarget(type,name);
                callback.fail(res);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                Target.ServiceTarget res = new Target.ServiceTarget(type,name);
                res.setPort(nsdServiceInfo.getPort());
                try {
                    res.setAddress(nsdServiceInfo.getHost(),null);
                } catch (URISyntaxException e) {
                    callback.fail(res);
                }
                callback.resolve(res);
            }
        });
        return true;
    }

    public List<String> discoveredServices(String type){
        ArrayList<String> rt=new ArrayList<>();
        ArrayList<NsdServiceInfo> foundServices=new ArrayList<>();
        synchronized (services){
            for (NsdServiceInfo info:services){
                if (type == null || type.equals(info.getServiceType())){
                    foundServices.add(info);
                }
            }
        }
        synchronized (registeredServices){
            for (NsdServiceInfo info:foundServices){
                boolean isOwn=false;
                for (Registration r:registeredServices.values()){
                    try {
                        if (r.info.getServiceName().equals(info.getServiceName())
                                && r.info.getServiceType().equals(info.getServiceType())
                        ) {
                            isOwn = true;
                            break;
                        }
                    }catch (Throwable t){}
                }
                if (! isOwn){
                    rt.add(info.getServiceName());
                }
            }
        }
        return rt;
    }

    public void registerService(int workerId,String type,String name, int port){
        if (nsdManager == null) return;
        AvnLog.ifs("register mdns name=%s,type=%s,port=%s",name,type,port);
        Registration old=null;
        synchronized (registeredServices){
            old=registeredServices.get(workerId);
            if (old != null){
                registeredServices.remove(workerId);
            }
            //we are sure that each worker regsitration can only run in one thread
        }
        if (old != null){
            try{
                unregisterService(old);
            }catch (Throwable t){
                AvnLog.e("error in unregisterAvahi",t);
            }
        }
        final NsdServiceInfo info=new NsdServiceInfo();
        info.setPort(port);
        info.setServiceType(type);
        info.setServiceName(name);
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {

            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {

            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                AvnLog.ifs("registerd service %s",serviceInfo);
                info.setHost(serviceInfo.getHost());
                info.setServiceName(serviceInfo.getServiceName());//maybe the name changed due to conflict
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {

            }
        };
        nsdManager.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener);
        synchronized (registeredServices) {
            registeredServices.put(workerId, new Registration(info, listener));
        }
    }

    public NsdServiceInfo getRegisteredService(int workerId){
        synchronized (registeredServices){
            Registration reg=registeredServices.get(workerId);
            if (reg == null) return null;
            return reg.info;
        }
    }
    public void unregisterService(int id){
        if (nsdManager == null) return;
        Registration reg=null;
        synchronized (registeredServices){
            reg=registeredServices.get(id);
            registeredServices.remove(id);
        }
        if (reg == null) return;
        try {
            nsdManager.unregisterService(reg.listener);
        }catch (Throwable t){}
    }

    private void unregisterService(Registration reg){
        if (nsdManager == null) return;
        nsdManager.unregisterService(reg.listener);
    }
    private void unregisterAllServices(){
        ArrayList<Registration> registrations=null;
        synchronized (registeredServices){
            registrations=new ArrayList<>(registeredServices.values());
            registeredServices.clear();
        }
        for (Registration r:registrations){
            try{
                unregisterService(r);
            }catch(Throwable t){}
        }
    }

    public void restart(){
        AvnLog.i(LOGPRFX,"service restart");
        stopWorkers(true);
        stopDiscovery();
        synchronized (services){
            services.clear();
        }
        unregisterAllServices();
        startDiscovery();
        handleStartup(false);
        AvnLog.i(LOGPRFX,"service restart done");
    }

    public void onCreate()
    {
        super.onCreate();
        notificationSend=false;
        lastNotifiedAlarm=null;
        ctx = this;
        mediaPlayer=new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                AvnLog.e("Media player error "+what+","+extra);
                return true;
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (mediaRepeatCount > 0) mediaRepeatCount--;
                if (mediaRepeatCount > 0){
                    mediaPlayer.start();
                }
            }
        });

        IntentFilter filter=new IntentFilter(Constants.BC_STOPALARM);
        broadCastReceiver=new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AvnLog.i("received stop alarm");
                resetAllAlarms();
                handleNotification(true,false, 0);
            }
        };
        AvnUtil.registerExportedReceiver(this,broadCastReceiver,filter);
        triggerReceiver=new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                RouteHandler routeHandler=getRouteHandler();
                if (routeHandler != null) routeHandler.triggerParser();
            }
        };
        IntentFilter triggerFilter=new IntentFilter((Constants.BC_TRIGGER));
        AvnUtil.registerUnexportedReceiver(this,triggerReceiver,triggerFilter);
        IntentFilter filterStop=new IntentFilter(Constants.BC_STOPAPPL);
        broadCastReceiverStop = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AvnLog.i("received stop appl");
                GpsService.this.stopMe();
            }
        };
        AvnUtil.registerExportedReceiver(this,broadCastReceiverStop,filterStop);
        IntentFilter pluginFilter=new IntentFilter(Constants.BC_PLUGIN);
        broadCastReceiverPlugin=new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String name=intent.getStringExtra("name");
                if (name == null) return;
                AvnLog.d("received plugin message "+name);
                handlePluginMessage(name,intent);
            }
        };
        AvnUtil.registerExportedReceiver(this,broadCastReceiverPlugin,pluginFilter);
        Intent watchdog = new Intent(getApplicationContext(), GpsService.class);
        watchdog.setAction(WATCHDOGACTION);
        watchdogIntent=PendingIntent.getService(
                getApplicationContext(),
                0,
                watchdog,
                AvnUtil.buildPiFlags(0,true));

        ((AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE))
                .setInexactRepeating(
                        AlarmManager.ELAPSED_REALTIME,
                        0,
                        Constants.WATCHDOGTIME,
                        watchdogIntent
                );

    }
    /**
     * a timer handler
     * we compare the sequence from the start with our current sequence to prevent
     * multiple runs...
     */
    private class TimerRunnable implements Runnable{
        private long sequence=0;
        TimerRunnable(long seq){sequence=seq;}
        public void run(){
            if (! isRunning) return;
            if (timerSequence != sequence) return;
            try {
                timerAction();
            } catch (JSONException e) {
                AvnLog.e("error in timer",e);
            }
            handler.postDelayed(this, timerInterval);
        }
    }

    public void timerAction() throws JSONException {
        AvnLog.i(LOGPRFX,"timer action");
        checkAnchor();
        checkApproach();
        checkMob();
        handleNotification(true,false, 0);
        checkTrackWriter();
        ArrayList<IWorker> allWorkers = new ArrayList<>();
        synchronized (this) {
            allWorkers.addAll(workers);
            allWorkers.addAll(internalWorkers);
        }
        for (IWorker w: allWorkers) {
            if (w != null) {
                try {
                    w.check();
                } catch (Throwable t) {
                    AvnLog.e("error in check for "+w.getTypeName());
                }
            }
        }
        SharedPreferences prefs=getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
        if (!prefs.getBoolean(Constants.ALARMSOUNDS,true)) {
            try{
                if (mediaPlayer != null){
                    mediaPlayer.reset();
                }
            }catch (Exception e){}
        }
        cleanupOldTmp();
    }

    private long lastCleanup=0;
    private static long CLEANUP_INTERVAL=1800*1000; //ms
    private void cleanupOldTmp(){
        if (requestHandler == null) return;
        long now=System.currentTimeMillis();
        if ((lastCleanup + CLEANUP_INTERVAL) < now){
            AvnLog.i("cleanup tmp files");
            requestHandler.cleanupTmpFiles();
            lastCleanup=now;
        }

    }

    private void checkAnchor() throws JSONException {
        RouteHandler routeHandler=getRouteHandler();
        if (routeHandler == null) return;
        if (! routeHandler.anchorWatchActive()){
            resetAlarm(Alarm.GPS.name);
            resetAlarm(Alarm.ANCHOR.name);
            gpsLostAlarmed=false;
            return;
        }
        Location current=getLocation();
        if (current == null){
            resetAlarm(Alarm.ANCHOR.name);
            if (gpsLostAlarmed) return;
            gpsLostAlarmed=true;
            setAlarm(Alarm.GPS.name);
            return;
        }
        resetAlarm(Alarm.GPS.name);
        gpsLostAlarmed=false;
        if (! routeHandler.checkAnchor(current)){
            resetAlarm(Alarm.ANCHOR.name);
            return;
        }
        setAlarm(Alarm.ANCHOR.name);
    }
    private void checkApproach() throws JSONException {
        RouteHandler routeHandler=getRouteHandler();
        if (routeHandler == null) return;
        Location current=getLocation();
        if (current == null){
            resetAlarm(Alarm.WAYPOINT.name);
            return;
        }
        if (! routeHandler.handleApproach(current)){
            lastAlarmWp=null;
            resetAlarm(Alarm.WAYPOINT.name);
            return;
        }
        if (lastAlarmWp == routeHandler.getCurrentTarget() ){
            return;
        }
        lastAlarmWp=routeHandler.getCurrentTarget();
        setAlarm(Alarm.WAYPOINT.name);
    }

    private void checkMob(){
        RouteHandler routeHandler=getRouteHandler();
        if (routeHandler == null) return;
        if (! routeHandler.mobActive()){
            mobAlarm=false;
            resetAlarm(Alarm.MOB.name);
            return;
        }
        if (mobAlarm) return;
        setAlarm(Alarm.MOB.name);
        mobAlarm=true;
    }


    /**
     * will be called whe we intend to really stop
     */
    private void handleStop(boolean resetRunning) {
        AvnLog.i(LOGPRFX,"handle stop");
        stopWorkers(false);
        if (requestHandler != null) requestHandler.stop();
        requestHandler=null;
        if (resetRunning){
            isRunning = false;
            ((AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE)).
                    cancel(watchdogIntent);
        }
        handleNotification(false, false, 0);
        AvnLog.i(LOGPRFX,"alarm deregistered");
        if (mediaPlayer != null){
            try{
                mediaPlayer.release();
            }catch (Exception e){

            }
        }
        for (BroadcastReceiver r: new BroadcastReceiver[]{broadCastReceiver,triggerReceiver,broadCastReceiverStop,usbReceiver,broadCastReceiverPlugin}){
            if (r != null){
                try{
                    unregisterReceiver(r);
                }catch(Throwable t){}
            }
        }
        lastNotifiedAlarm=null;
        notificationSend=false;
        MainActivityActions cb=mBinder.getCallback();
        if (cb != null){
            cb.mainShutdown();
        }
        stopDiscovery();
        AvnLog.i(LOGPRFX, "service stopped");
    }

    public MainActivityActions getMainActions(){
        return mBinder.getCallback();
    }

    public void stopMe(){
        AvnLog.i(LOGPRFX,"stopMe");
        handleStop(true);
        stopSelf();
    }


    @Override
    public void onDestroy()
    {
        super.onDestroy();
        AvnLog.i(LOGPRFX,"service onDestroy");
        handleStop(false);
    }


    /**
     * will be called from within the timer thread
     * check if position has changed and write track entries
     * write out the track file in regular intervals
     */
    private void checkTrackWriter() throws JSONException {
        if (! isRunning) return;
        Location l=getLocation();
        TrackWriter trackWriter=getTrackWriter();
        if (trackWriter != null) trackWriter.checkWrite(l,mediaUpdater);

    }

    public boolean isRunning(){
        return isRunning;
    }




    public Map<String,Alarm> getAlarmStatus() {
        return alarmStatus;
    }
    public JSONObject getAlarStatusJson() throws JSONException {
        Map<String,Alarm> alarms=getAlarmStatus();
        JSONObject rt=new JSONObject();
        for (String k: alarms.keySet()){
            rt.put(k,alarms.get(k).toJson());
        }
        return rt;
    }

    public void resetAlarm(String type){
        Alarm a=alarmStatus.get(type);
        if (a != null && a.isPlaying){
            if (mediaPlayer != null) mediaPlayer.stop();
            mediaRepeatCount=0;
            alarmSequence++;
        }
        if (a != null)  {
            AvnLog.i("reset alarm "+type);
        }
        alarmStatus.remove(type);
    }
    public void resetAllAlarms(){
        ArrayList<String> alarms=new ArrayList<String>();
        for (String type: alarmStatus.keySet()){
            alarms.add(type);
        }
        for (String alarm: alarms){
            resetAlarm(alarm);
        }
    }
    public Alarm getCurrentAlarm(){
        if (alarmStatus.size() == 0) return null;
        Alarm activeAlarm=null;
        Alarm soundAlarm=null;
        for (Alarm alarm:alarmStatus.values()){
           if (alarm.running && activeAlarm==null) activeAlarm=alarm;
           if (alarm.isPlaying && soundAlarm == null) soundAlarm=alarm;
        }
        if (soundAlarm != null) return soundAlarm;
        return activeAlarm;
    }

    private void setAlarm(String type){
        Alarm a=Alarm.createAlarm(type);
        if (a == null) return;
        a.running=true;
        Alarm current=alarmStatus.get(a.name);
        if (current != null && current.running) return;
        alarmSequence++;
        AvnLog.i("set alarm "+type);
        alarmStatus.put(type,a);
        SharedPreferences prefs=getSharedPreferences(Constants.PREFNAME,Context.MODE_PRIVATE);
        if (!prefs.getBoolean(Constants.ALARMSOUNDS,true)) {
            try{
                if (mediaPlayer != null){
                    mediaPlayer.reset();
                }
            }catch(Exception e){}
            return;
        }
        AudioEditTextPreference.AudioInfo sound = AudioEditTextPreference.getAudioInfoForAlarmName(a.name,this);
        if (sound != null) {
            try {
                if (mediaPlayer != null) {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.reset();
                    mediaPlayer.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
                    AudioEditTextPreference.setPlayerSource(mediaPlayer,sound,this);
                    mediaRepeatCount=a.repeat;
                    mediaPlayer.setLooping(false);
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                }
                a.isPlaying = true;
            } catch (Exception e) {
            }
        }

    }

    public JSONObject getGpsData() throws JSONException{
        Decoder dec=getDecoder();
        JSONObject rt=dec!=null?dec.getGpsData():null;
        if (rt == null){
            rt=new JSONObject();
        }
        rt.put("updatealarm",alarmSequence);
        long legSequence=-1;
        RouteHandler routeHandler=getRouteHandler();
        if (routeHandler != null) legSequence=routeHandler.getLegSequence();
        rt.put("updateleg",legSequence);
        synchronized (configSequenceLock){
            rt.put("updateconfig",configSequence);
        }
        if (avnavVersion != 0) {
            rt.put("version", avnavVersion);
        }
        return rt;
    }

    private Location getLocation() throws JSONException {
        Decoder dec=getDecoder();
        return dec!=null?dec.getLocation():null;
    }

    public JSONArray getAisData(List<Location> centers, double distance){
        Decoder dec=getDecoder();
        return dec != null?dec.getAisData(centers,distance):null;
    }

    public void setMediaUpdater(IMediaUpdater u) {
        mediaUpdater = u;
        NmeaLogger logger = getNmeaLogger();
        if (mediaUpdater != null && logger != null) {
            logger.setMediaUpdater(mediaUpdater);
        }
    }

    public IMediaUpdater getMediaUpdater(){
        return mediaUpdater;
    }


    /**
     * get the status for NMEA and AIS
     * @return
     * nmea: { source: internal, status: green , info: 3 visible/2 used}
     * ais: [ source: IP, status: yellow, info: connected to 10.222.9.1:34567}
     * @throws JSONException
     */
    public JSONObject getNmeaStatus() throws JSONException {
        JSONObject nmea = new JSONObject();
        nmea.put("source", "unknown");
        nmea.put("status", "red");
        nmea.put("info", "disabled");
        JSONObject ais = new JSONObject();
        ais.put("source", "unknown");
        ais.put("status", "red");
        ais.put("info", "disabled");
        Decoder decoder=getDecoder();
        if (decoder != null) {
            Decoder.SatStatus st = decoder.getSatStatus();
            nmea.put("source", st.getSource());
            if (st.hasValidPosition()) {
                nmea.put("status", "green");
                nmea.put("info", "sats: " + st.getNumSat() + " / " + st.getNumUsed());
            } else {
                if (st.isGpsEnabled()) {
                    nmea.put("info", "con, sats: " + st.getNumSat() + " / " + st.getNumUsed());
                    nmea.put("status", "yellow");
                } else {
                    nmea.put("info", "disconnected");
                    nmea.put("status", "red");
                }
            }
            ais.put("source", decoder.getLastAisSource());
            int aisTargets = decoder.numAisData();
            if (aisTargets > 0) {
                ais.put("status", "green");
                ais.put("info", aisTargets + " targets");
            } else {
                if (st.isGpsEnabled()) {
                    ais.put("info", "connected");
                    ais.put("status", "yellow");
                } else {
                    ais.put("info", "disconnected");
                    ais.put("status", "red");
                }
            }
        }
        JSONObject rt = new JSONObject();
        rt.put("nmea", nmea);
        rt.put("ais", ais);
        return rt;
    }
    public synchronized JSONArray getStatus() throws JSONException {
        JSONArray rt=new JSONArray();
        for (IWorker w: internalWorkers){
            rt.put(w.getJsonStatus());
        }
        for (IWorker w : workers){
            rt.put(w.getJsonStatus());
        }
        return rt;
    }
    private List<List<IWorker>> getAllWorkers(){
        List<List<IWorker>> rt=new ArrayList<List<IWorker>>();
        rt.add(workers);
        rt.add(internalWorkers);
        return rt;
    }
    private void handlePluginMessage(String name, Intent intent){
        PluginWorker existing=null;
        synchronized (this) {
            if (! isRunning) return; //prevent duplicates...
            for (List<IWorker> wlist : getAllWorkers()) {
                for (IWorker w : wlist) {
                    if (w.getTypeName().equals(PluginWorker.TYPENAME)) {
                        PluginWorker pw = (PluginWorker) w;
                        if (pw.getPluginName().equals(name)) {
                            //found existing
                            existing = pw;
                            break;
                        }
                    }
                }
            }
        }
        if (existing != null){
            existing.update(intent);
            return;
        }
        //not found
        if (! allowAllPlugins) {
            return;
        }
        PluginWorker pw=new PluginWorker(this,name);
        try {
            addWorker(pw,new JSONObject());
        } catch (Exception e) {
            AvnLog.e("unable to add new plugin worker "+name,e);
        }
    }


}
