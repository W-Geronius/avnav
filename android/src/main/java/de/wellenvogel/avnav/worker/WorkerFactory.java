package de.wellenvogel.avnav.worker;

import android.content.Context;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.wellenvogel.avnav.util.NmeaQueue;

public class WorkerFactory {
    public static final String ANDROID_NAME="InternalGPS";
    public static final String USB_NAME="UsbConnection";
    public static final String SOCKETREADER_NAME="SocketReader";
    public static final String SOCKETWRITER_NAME="SocketWriter";
    public static final String BLUETOOTH_NAME="Bluetooth";
    private static final WorkerFactory instance=new WorkerFactory();
    public static WorkerFactory getInstance(){return instance;}
    public static class WorkerNotFound extends Exception{
        public WorkerNotFound(String name) {
            super("Worker "+name+" not found");
        }
    }
    static abstract class Creator{
        abstract ChannelWorker create(String name,Context ctx,NmeaQueue queue) throws JSONException, IOException;
        boolean canAdd(Context ctx){return true;}
    }
    public WorkerFactory(){
        registerCreator(ANDROID_NAME,new AndroidPositionHandler.Creator());
        registerCreator(SOCKETREADER_NAME,new SocketReader.Creator());
        registerCreator(SOCKETWRITER_NAME, new SocketWriter.Creator());
        registerCreator(USB_NAME,new UsbConnectionHandler.Creator());
        registerCreator(BLUETOOTH_NAME, new BluetoothConnectionHandler.Creator());

    }
    private HashMap<String, Creator> workers=new HashMap<>();
    void registerCreator(String typeName,Creator creator){
        workers.put(typeName,creator);
    }
    public ChannelWorker createWorker(String name,Context ctx, NmeaQueue queue) throws WorkerNotFound, JSONException, IOException {
        Creator cr=workers.get(name);
        if ( cr == null) throw new WorkerNotFound(name);
        return cr.create(name,ctx,queue);
    }
    public List<String> getKnownTypes(boolean addOnly,Context ctx){
        ArrayList<String> rt=new ArrayList<>();
        for (String n:workers.keySet()){
            if (!addOnly || workers.get(n).canAdd(ctx)){
                rt.add(n);
            }
        }
        return rt;
    }
}