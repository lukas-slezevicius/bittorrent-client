package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.zip.DataFormatException;

class Torrent {
    private Metainfo metainfo;
    private Tracker tracker;
    private String peerId;
    private int port;
    private int downloaded;
    private int uploaded;

    Torrent(String filepath, String peerId, int port) throws DataFormatException, IOException, URISyntaxException {
        this.port = port;
        this.peerId = peerId;
        metainfo = new Metainfo(filepath);
        tracker = new Tracker(metainfo, this);
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
}