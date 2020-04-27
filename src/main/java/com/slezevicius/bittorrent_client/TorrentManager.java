package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

/**
 * The manager of all the currently active torrents. It detects newly added torrent files
 * and creates new Torrent instances for those files. Upon shutdown, it shuts down all the
 * currently active Torrent instances.
 */
public class TorrentManager {
    /**
     * torrents variable keeps track of what torrent file corresponds to what Torrent instance.
     */
    private HashMap<File, Torrent> torrents;
    private PeerServer peerServer;
    private File torrentDir;
    private File saveDir;
    private int port;
    private String peerId;
    private Logger log;

    /**
     * @param torrentPath: the path where all the torrent files are kept by the client.
     * @param savePath: the path where to save all the downloaded files.
     * @param port: the port which is used for listening to new peers.
     * @param peerId: the peerId for the torrent client
     * @throws IOException: thrown if the peer server could not start up.
     */
    TorrentManager(String torrentPath, String savePath, int port, String peerId) throws IOException {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        torrentDir = new File(torrentPath);
        saveDir = new File(savePath);
        peerServer = new PeerServer(this);
        this.port = port;
        this.peerId = peerId;
    }

    /**
     * Called by the client whenever there are new files to be checked.
     * Finds all the new torrent files and initializes their corresponding Torrent
     * instances.
     */
    public void updateFiles() {
        for (File file : torrentDir.listFiles()) {
            if (file.isFile() && !torrents.containsKey(file)) {
                try {
                    Torrent tor = new Torrent(this, file, saveDir);
                    torrents.put(file, tor);
                    log.info("Added new torrent " + file.getName());
                } catch (DataFormatException | URISyntaxException | IOException e) {
                    log.log(Level.INFO, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Finds the Torrent instance which deals with the given peer's
     * torrent file and then orders it to add it to its peer manager.
     * @param peer
     */
    public void receivedPeer(Peer peer) {
        for (Torrent tor : torrents.values()) {
            if (Arrays.equals(tor.getInfoHash(), peer.getInfoHash())) {
                tor.addPeer(peer);
            }
        }
    }
    
    /** 
     * @return String
     */
    public String getPeerId() {
        return peerId;
    }
    
    /** 
     * @return int
     */
    public int getPort() {
        return port;
    }

    /** 
     * Graciously shuts down the torrent manager, its peer server, and
     * all the torrent instances it has started.
     * @throws InterruptedException: if an interrupt was received while waiting
     * for the peer server to shut.
     */
    public void shutdown() throws InterruptedException {
        log.info("Shutting down torrent manager");
        peerServer.shutdown();
        for (Torrent tor : torrents.values()) {
            tor.shutdown();
        }
        peerServer.join();
    }
}