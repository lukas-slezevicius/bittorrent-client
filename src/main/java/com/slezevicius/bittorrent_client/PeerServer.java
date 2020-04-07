package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

public class PeerServer extends Thread {
    private PeerManager peerManager;
    private int port;
    private ServerSocket server;
    private volatile boolean keepRunning = true;
    private Logger log;

    PeerServer(PeerManager peerManager, int port) throws IOException {
        this.log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        this.log.setLevel(Level.ALL);
        this.peerManager = peerManager;
        this.port = port;
        this.server = new ServerSocket(port);
    }

    public void run() {
        while (true) {
            try {
                Socket sock = server.accept();
                Peer peer = new Peer(sock, peerManager);
                peer.start();
            } catch (SecurityException e) {
                log.log(Level.WARNING, e.getMessage(), e);
            } catch(DataFormatException e) {
                log.log(Level.WARNING, e.getMessage(), e);
            } catch (SocketException e) {
                log.log(Level.WARNING, e.getMessage(), e);
                log.info("Shutting down peer server through SocketException");
                return;
            } catch (IOException e) {
                log.log(Level.WARNING, e.getMessage(), e);
            } catch (InterruptedException e) {
                log.log(Level.WARNING, e.getMessage(), e);
            }
            synchronized(this) {
                if (!keepRunning) {
                    log.info("Shutting down peer server through keepRunning");
                    return;
                }
            }
        }
    }

    public synchronized void stopRunning() {
        keepRunning = false;
        try {
            server.close();
        } catch (IOException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        }
    }
}