package com.slezevicius.bittorrent_client;

import java.io.IOException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;


public class App 
{
    public static volatile boolean keepRunning = true;

    public static void main( String[] args)
    {
        final Logger log = LogManager.getFormatterLogger(App.class);
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                keepRunning = false;
                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    log.warn("Interrupted while joining main thread");
                } finally {
                    log.info("Shutting down logger");
                    LogManager.shutdown();
                }
            }
        });

        log.info("Starting bittorrent client");
        int port = 6881;
        String peerId = "-XX0100-000000000000";
        String path = "/home/lukas/Programming/Projects/bittorrent-client/";
        String torrentPath = path + "Torrents";
        String downloadPath = path + "Downloaded";
        TorrentManager torrentManager;
        try {
            log.trace("Starting the torrent manager");
            torrentManager = new TorrentManager(torrentPath, downloadPath, port, peerId);
        } catch (IOException e) {
            log.error("IOException while initializing torrent manager", e);
            return;
        }
        torrentManager.updateFiles();

        log.trace("Starting the client main loop");
        while (keepRunning) {
            //Keep checking for new file additions here
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                log.error("Client was interrupted while in the main loop.", e);
            }
        }
        log.trace("Starting to shut down the bittorrent client");
        try {
            torrentManager.shutdown();
        } catch (InterruptedException e) {
            log.warn("Could not shut down bittorrent client properly");
            return;
        }
        log.info("Bittorrent client has been properly shut down");
    }
}