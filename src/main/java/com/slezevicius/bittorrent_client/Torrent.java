package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

public class Torrent extends Thread {
    private Metainfo metainfo;
    private Tracker tracker;
    private PeerManager peerManager;
    private HashSet<Peer> peers;
    private String peerId;
    private int port;
    private int downloaded;
    private int uploaded;
    private volatile boolean keepRunning = true;
    private Logger log;

    Torrent(String filepath, String peerId, int port) throws DataFormatException, IOException, URISyntaxException {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.port = port;
        this.peerId = peerId;
        metainfo = new Metainfo(filepath);
        tracker = new Tracker(metainfo, this);
        tracker.start();
        peerManager = new PeerManager(getBitfieldLength());
        peers = new HashSet<>(50);
        log.info("Initialized torrent " + this.toString());
    }

    public void run() {
        try {
            while (tracker.getPeers() == null) {
                Thread.sleep(100);
            }
            while (true) {
                if (peers.size() <= 50) {
                    for (Pair<InetAddress, Integer> pair : tracker.getPeers()) {
                        Peer peer = new Peer(pair, this, peerManager); 
                        if (!peers.contains(peer)) {
                            peers.add(peer);
                            peer.start();
                        }
                    }
                }
                synchronized(this) {
                    if (!keepRunning) {
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            return;
        }
        shutdown();
    }

    public int getDownloaded() {
        return downloaded;
    }

    public int getUploaded() {
        return uploaded;
    }

    public String getPeerId() {
        return peerId;
    }

    public int getPort() {
        return port;
    }

    public byte[] getInfoHash() {
        return metainfo.getInfoHash();
    }

    public int getBitfieldLength() {
        return (int) ((metainfo.getPieces().length/20)/8 + 1);
    }

    public synchronized void stopRunning() {
        keepRunning = false;
    }

    private void shutdown() {
        tracker.stopRunning();
        for (Peer peer : peers) {
            peer.stopRunning();
        }
        peerManager.stopRunning();
        try {
            for (Peer peer : peers) {
                peer.join();
            }
            tracker.join();
            peerManager.join();
        } catch (InterruptedException e) {
            log.warning(this.toString() + " Could not properly close all child threads");
            return;
        }
        log.info(this.toString() + " Closed all child threads.");
    }

    @Override
    public String toString() {
        return "Torrent[name=" + metainfo.getName() + "]";
    }
}