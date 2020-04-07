package com.slezevicius.bittorrent_client;

public class TorrentManager {
    private PeerManager peerManager;

    TorrentManager(PeerManager peerManager) {
        this.peerManager = peerManager;
    }
}