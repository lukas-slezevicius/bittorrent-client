package com.slezevicius.bittorrent_client;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class TorrentManager {
    private PeerManager peerManager;
    private volatile ArrayList<Torrent> torrentList;

    public void addPeerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    public synchronized void addTorrent(String metainfoPath, String savePath) {
        Torrent tor = new Torrent(metainfoPath);
        torrentList.add(tor);
        peerManager.getFileManager().add(tor, savePath);
        peerManager.checkTorrent();
        tor.start();
    }

    public synchronized Torrent getTorrent(String hexInfoHash) {
        for (Torrent tor : torrentList) {
            if (tor.getHexInfoHash() == hexInfoHash) {
                return tor;
            }
        }
        return null;
    }
    
    public HashMap<String, HashSet<Pair<InetAddress, Integer>>> getPeers() {
        HashMap<String, HashSet<Pair<InetAddress, Integer>>> peers;
        synchronized(this) {
            peers = new HashMap<>(torrentList.size());
        }
        for (Torrent tor : torrentList) {
            peers.put(tor.getHexInfoHash(), tor.getPeers());
        }
    }

    public void shutdown() {
        for (Torrent tor : torrentList) {
            tor.shutdown();
        }
        //Initializing all shutdowns before joining would be quicker to finish
        for (Torrent tor : torrentList) {
            tor.join();
        }
    }
}