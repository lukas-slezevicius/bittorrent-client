package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

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
        this.log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        this.log.setLevel(Level.ALL);
        this.server = new ServerSocket(torrentManager.getPort());
        this.torrentManager = torrentManager;
        this.run = true;
    }

    /**
     * Continously listens for new incoming connections and deals
     * with newly connected peers. Once the origin of peer is determined
     * it informs torrent manager to add the peer to the needed peer manager.
     */
    @Override
    public void run() {
        while (true) {
            try {
                Socket sock = server.accept();
                try {
                    Peer peer = new Peer(sock);
                    torrentManager.receivedPeer(peer);
                } catch (IOException | DataFormatException | InterruptedException e) {
                    log.log(Level.FINE, e.getMessage(), e);
                }
            } catch (SecurityException | IOException e) {
                log.log(Level.WARNING, e.getMessage(), e);
                break;
            }
            synchronized(this) {
                if (!run) {
                    log.info("Shutting down peer server from run");
                    return;
                }
            }
        }
    }

    /**
     * Graciously shuts down the peer server.
     */
    public synchronized void shutdown() {
        log.info("Shutting down peer server");
        run = false; //If the peer is not listening, it could break earlier
        try {
            server.close();
        } catch (IOException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        }
    }
}