package com.slezevicius.bittorrent_client;

public class TorrentPeerInfo {
    public String hexInfoHash;
    public int requestCount;
    public Peer peer;
    public boolean isSeeding;
    public boolean isCompleted;

    TorrentPeerInfo(String hexInfoHash, Peer peer) {
        this.hexInfoHash = hexInfoHash;
        this.peer = peer;
        this.isSeeding = true;
        this.requestCount = 0;
        this.isCompleted = false;
    }
}