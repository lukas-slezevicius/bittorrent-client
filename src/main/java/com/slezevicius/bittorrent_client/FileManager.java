package com.slezevicius.bittorrent_client;

import java.io.File;

public class FileManager {
    private Torrent tor;
    private File saveFile;

    FileManager(Torrent tor, File saveFile) {
        this.tor = tor;
        this.saveFile = saveFile;
        //Read the initial bitfield if the file exists
    }

    public synchronized byte[] getBitfield() {

    }

    public synchronized void readFile(Request req) {

    }

    public synchronized void writeFile(Request req) {

    }

    public synchronized boolean isComplete() {

    }

    public synchronized int getDownloaded() {

    }

    public synchronized int getUploaded() {

    }

    public synchronized int[] getHaves() {

    }

    public void shutdown() {

    }
}