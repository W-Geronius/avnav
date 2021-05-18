package de.wellenvogel.avnav.appapi;

public interface IWebSocketHandler {

    public void onReceive(int opCode, byte[] msg, IWebSocket socket);
    public void onConnect(IWebSocket socket);
    public void onClose(IWebSocket socket);
    public void onError(String error,IWebSocket socket);
}
