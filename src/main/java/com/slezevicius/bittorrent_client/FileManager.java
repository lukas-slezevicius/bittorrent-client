package com.slezevicius.bittorrent_client;

public class FileManager extends Thread {
    private PeerManager peerManager;
    private TorrentManager torrentManager;

    FileManager(PeerManager peerManager, TorrentManager torrentManager) {
        this.peerManager = peerManager;
        this.torrentManager = torrentManager;
    }

    @Override
    public void run() {
        while (true) {

        }
    }
}