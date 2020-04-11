package com.slezevicius.bittorrent_client;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

public class App 
{
    public static volatile boolean keepRunning = true;

    static {
        System.setProperty("java.util.logging.manager", MyLogManager.class.getName());
    }

    public static class MyLogManager extends LogManager {
        static MyLogManager instance;

        public MyLogManager() {
            instance = this;
        }

        @Override
        public void reset() {

        }

        private void reset0() {
            super.reset();
        }

        public static void resetFinally() {
            instance.reset0();
        }


    }

    public static void main( String[] args )
    {
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                keepRunning = false;
                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                } finally {
                    MyLogManager.resetFinally();
                }
            }
        });

        Logger log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        setupLoggingConfig("/log.properties");

        log.info("Starting bittorrent client");
        int port = 6881;
        String peerId = "-XX0100-000000000000";
        TorrentManager torrentManager = new TorrentManager();
        PeerManager peerManager;
        try {
            peerManager = new PeerManager(peerId, port);
        } catch (IOException e) {
            log.log(Level.WARNING, e.getMessage(), e);
            return;
        }
        torrentManager.addPeerManager(peerManager);
        peerManager.addTorrentManager(torrentManager);
        peerManager.start();
        String path = "/home/lukas/Programming/Projects/bittorrent-client/";
        String file = "ubuntu-19.10-desktop-amd64.iso.torrent";
        Torrent tor;
        try {
            tor = new Torrent(path + file, "-XX0100-000000000000", 6881);
        } catch (IOException e) {
            log.log(Level.SEVERE, e.getMessage(), e);
            return;
        } catch (DataFormatException e) {
            log.log(Level.SEVERE, e.getMessage(), e);
            return;
        } catch (URISyntaxException e) {
            log.log(Level.SEVERE, e.getMessage(), e);
            return;
        }
        tor.start();

        while (keepRunning) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {}
        }
        log.info("Shutting down bittorrent client");
        tor.stopRunning();
        try {
            tor.join();
        } catch (InterruptedException e) {
            log.warning("Could not shut down bittorrent client properly");
            return;
        }
        log.info("Bittorrent client properly shut down");
    }

    static void setupLoggingConfig(String configName) {
        try {
            String configPath = System.getProperty("user.dir") + configName;
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);
            configFile.close();
        } catch (IOException e) {
            System.out.println("WARNING: Could not open configuration file");
            System.out.println("WARNING: Logging not configured (console output only)");
        }
    }
}