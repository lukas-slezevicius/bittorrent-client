package com.slezevicius.bittorrent_client;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

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
                    mainThread.interrupt();
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
        String sembuchaDir = System.getProperty("user.home") + "/.local/share/Sembucha";
        String torrentPath = sembuchaDir + "/Torrents";
        int port = 6881;
        String peerId = "-XX0100-000000000000";
        TorrentManager torrentManager;
        try {
            log.trace("Starting the torrent manager");
            torrentManager = new TorrentManager(torrentPath, port, peerId);
        } catch (IOException e) {
            log.error("IOException while initializing torrent manager", e);
            return;
        }

        log.trace("Starting the client main loop");
        WatchService watchService = null;
        WatchKey watchKey;
        Properties torrentProperties = new Properties();
        FileInputStream fis;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path watchDir = Paths.get(sembuchaDir);
            watchKey = watchDir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            );
            fis = new FileInputStream(sembuchaDir + "/torrents.properties");
            torrentProperties.load(fis);
        } catch(IOException e) {
            log.error(e.getMessage(), e);
            keepRunning = false;
        }
        Set<String> torrentFiles = new HashSet<>();
        for (String file : torrentProperties.stringPropertyNames()) {
            log.trace("App is updating file %s", file);
            torrentFiles.add(file);
            torrentManager.updateFile(file, torrentProperties.getProperty(file));
        }
        while (keepRunning) {
            try {
                watchKey = watchService.take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                }
                torrentProperties = new Properties();
                fis = new FileInputStream(sembuchaDir + "/torrents.properties");
                torrentProperties.load(fis);
                for (String file : torrentProperties.stringPropertyNames()) {
                    log.trace("App is updating file %s", file);
                    torrentManager.updateFile(file, torrentProperties.getProperty(file));
                }
                Iterator<String> it = torrentFiles.iterator();
                while (it.hasNext()) {
                    String file = it.next();
                    if (!torrentProperties.stringPropertyNames().contains(file)) {
                        log.trace("App is removing file %s", file);
                        it.remove();
                        torrentManager.removeFile(file);
                    }
                }
                watchKey.reset();
            } catch (InterruptedException | IOException e) {
                log.error(e.getMessage(), e);
            }
        }
        try {
            watchService.close();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
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