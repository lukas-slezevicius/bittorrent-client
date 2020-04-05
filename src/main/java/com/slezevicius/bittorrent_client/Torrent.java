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
        this.port = port;
        this.peerId = peerId;
        metainfo = new Metainfo(filepath);
        tracker = new Tracker(metainfo, this);
        tracker.start();
        peerManager = new PeerManager(getBitfieldLength());
        peers = new HashSet<>();
        log.info("Initialized torrent " + this.toString());
    }

    public void run() {
        try {
            for (Pair<InetAddress, Integer> pair : tracker.getPeers()) {
                /*
                try {
                    Peer peer = new Peer(pair, this, peerManager); 
                    peers.add(peer);
                    peer.start();
                } catch (IOException e) {
                    e.printStackTrace();
                    continue;
                }
                */
                break;
            };
            while (keepRunning) {
                Thread.sleep(50);
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

    public void stopRunning() {
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