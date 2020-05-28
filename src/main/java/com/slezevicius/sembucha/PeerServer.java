package com.slezevicius.sembucha;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.zip.DataFormatException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PeerServer continously listens for new incoming connections from other peers.
 * Upon receiving a new peer and deciding what torrent it belongs to, it informs
 * the torrent manager about the peer.
 */
public class PeerServer extends Thread {
    private ServerSocket server;
    private TorrentManager torrentManager;
    private volatile boolean run;
    private Logger log;

    /**
     * @param torrentManager: The torrent manager which owns the server.
     * @throws IOException: whenever a new server socket cannot be created.
     */
    PeerServer(TorrentManager torrentManager) throws IOException {
        log = LogManager.getFormatterLogger(PeerServer.class);
        this.server = new ServerSocket(torrentManager.getPort());
        this.torrentManager = torrentManager;
        this.run = true;
        log.trace("PeerServer initialized");
    }

    /**
     * Continously listens for new incoming connections and deals
     * with newly connected peers. Once the origin of peer is determined
     * it informs torrent manager to add the peer to the needed peer manager.
     */
    @Override
    public void run() {
        log.trace("PeerServer in the main loop");
        while (true) {
            try {
                synchronized(this) {
                    if (!run) {
                        log.trace("Shutting down peer server from run");
                        server.close();
                        return;
                    }
                }
                Socket sock = server.accept();
                try {
                    Peer peer = new Peer(sock);
                    torrentManager.receivedPeer(peer);
                } catch (IOException | DataFormatException | InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            } catch (IOException e) {
                synchronized(this) {
                    if (!run) {
                        log.trace("Server socket is closed");
                        return;
                    }
                }
                log.error(e.getMessage(), e);
                return;
            }
        }
    }

    /**
     * Graciously shuts down the peer server.
     */
    public synchronized void shutdown() {
        log.trace("Shutting down peer server");
        try {
            server.close();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        run = false;
    }
}