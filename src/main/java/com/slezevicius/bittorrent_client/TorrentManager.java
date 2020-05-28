package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.DataFormatException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The manager of all the currently active torrents. It detects newly added
 * torrent files and creates new Torrent instances for those files. Upon
 * shutdown, it shuts down all the currently active Torrent instances.
 */
public class TorrentManager {
    /**
     * torrents variable keeps track of what torrent file corresponds to what
     * Torrent instance.
     */
    private HashMap<File, Pair<Torrent, String>> torrents;
    private PeerServer peerServer;
    private File torrentDir;
    private int port;
    private String peerId;
    private Logger log;

    /**
     * @param torrentPath: the path where all the torrent files are kept by the
     *                     client.
     * @param savePath:    the path where to save all the downloaded files.
     * @param port:        the port which is used for listening to new peers.
     * @param peerId:      the peerId for the torrent client
     * @throws IOException: thrown if the peer server could not start up.
     */
    TorrentManager(String torrentPath, int port, String peerId) throws IOException {
        log = LogManager.getFormatterLogger(TorrentManager.class);
        log.trace("Initializing the torrent manager");
        torrentDir = new File(torrentPath);
        peerServer = new PeerServer(this);
        peerServer.start();
        torrents = new HashMap<>();
        this.port = port;
        this.peerId = peerId;
        log.trace("Finished initializing the torrent manager");
    }

    TorrentManager() {
        // For testing
    }

    public void updateFile(String fileName, String state) {
        String run = state.substring(0, state.indexOf(','));
        File downloadPath = new File(state.substring(state.indexOf(',') + 1));
        File file = new File(torrentDir.getAbsolutePath() + "/" + fileName);
        if (torrents.containsKey(file)) {
            Torrent tor = torrents.get(file).getLeft();
            if (!torrents.get(file).getRight().equals(run)) {
                log.info("Changing state of %s from %s to %s", tor.toString(), torrents.get(file).getRight(), state);
                switch (run) {
                    case "run":
                        try {
                            tor.startRunning();
                        } catch (DataFormatException | URISyntaxException | IOException e) {
                            log.error(e.getMessage(), e);
                            return;
                        }
                        break;
                    case "stop":
                        try {
                            tor.shutdown();
                        } catch (InterruptedException e) {
                            log.error(e.getMessage(), e);
                            return;
                        }
                        break;
                    default:
                        log.warn("Invalid state written to sembucha.properties: %s", run);
                        return;
                }
                torrents.put(file, new Pair<>(tor, run));
            }
            if (!torrents.get(file).getLeft().getSaveFile().equals(downloadPath)) {
                log.info("Changing download path of %s to %s", tor.toString(), downloadPath);
                tor.changeDownloadPath(downloadPath);
            }
        } else {
            try {
                Torrent tor = new Torrent(this, file, downloadPath);
                switch (run) {
                    case "run":
                        tor.startRunning();
                        break;
                    case "stop":
                        break;
                    default:
                        log.warn("Invalid state written to sembucha.properties: %s", run);
                        return;
                }
                torrents.put(file, new Pair<>(tor, run));
            } catch (DataFormatException | URISyntaxException | IOException e) {
                log.error(e.getMessage(), e);
            }
            log.info("Added new torrent %s", file.getName());
        }
    }

    public void removeFile(String fileName) {
        File file = new File(torrentDir.getAbsolutePath() + "/" + fileName);
        if (torrents.containsKey(file)) {
            log.info("Removing %s from torrents", fileName);
            Torrent tor = torrents.get(file).getLeft();
            try {
                if (torrents.get(file).getRight().equals("run")) {
                    tor.shutdown();
                }
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
            torrents.remove(file);
        } else {
            log.info("Requested to remove %s and it did not exist", fileName);
        }
    }


    /**
     * Finds the Torrent instance which deals with the given peer's
     * torrent file and then orders it to add it to its peer manager.
     * @param peer
     */
    public void receivedPeer(Peer peer) {
        log.debug("Received a new %s from the peer server", peer.toString());
        for (Pair<Torrent, String> pair : torrents.values()) {
            if (Arrays.equals(pair.getLeft().getInfoHash(), peer.getInfoHash())) {
                pair.getLeft().addPeer(peer);
                return;
            }
        }
        log.warn("Could not find a matching torrent for the peer %s with infohash %s",
            peer.toString(), Metainfo.bytesToHex(peer.getInfoHash()));
        peer.shutdownSockets();
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
        log.trace("Shutting down torrent manager");
        peerServer.shutdown();
        for (Pair<Torrent, String> pair : torrents.values()) {
            pair.getLeft().shutdown();
        }
        peerServer.join();
        log.trace("Successfully shut down the torrent manager");
    }
}