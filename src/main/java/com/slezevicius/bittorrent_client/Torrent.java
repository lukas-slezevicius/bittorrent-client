package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

/**
 * The class that deals with each particular torrent file. It controls
 * its peer manager, tracker, metainfo, and file manager instances. It acts as
 * a communication intermediary amongst its controlled classes.
 */
public class Torrent {
    private TorrentManager torrentManager;
    private Metainfo metainfo;
    private Tracker tracker;
    private FileManager fileManager;
    private PeerManager peerManager;
    private Logger log;

    /**
     * Initializes all the controlled classes.
     * @param torrentManager
     * @param torrentFile
     * @param saveFile
     * @throws DataFormatException
     * @throws URISyntaxException
     * @throws IOException
     */
    Torrent(TorrentManager torrentManager, File torrentFile, File saveFile)
            throws DataFormatException, URISyntaxException , IOException {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.torrentManager = torrentManager;
        metainfo = new Metainfo(torrentFile);
        tracker = new Tracker(metainfo, this);
        tracker.start();
        fileManager = new FileManager(this, saveFile);
        peerManager = new PeerManager(this);
        log.info("Initialized torrent"); //Add proper logging
    }
    
    /** 
     * Checks whether the tracker has any new peer information.
     * @return boolean
     */
    public boolean newPeers() {
        return tracker.newPeers();
    }

    /** 
     * Returns the new peer information from the tracker.
     * @return ArrayList<Pair<InetAddress, Integer>>
     */
    public List<Pair<InetAddress, Integer>> getNewPeers() {
        return tracker.getNewPeers();
    }

    public void redownloadPiece(int index) {
        peerManager.redownloadPiece(index);
    }

    /**
     * @return boolean indicating whether the downloading process is complete.
     */
    public boolean isComplete() {
        return fileManager.isComplete();
    }

    /**
     * @return boolean indicating whether the user wants this torrent to be uploading.
     */
    public boolean isUploading() {
        return false;
    }

    /**
     * @return boolean indicating whether the user want this torrent to be downloading.
     */
    public boolean isDownloading() {
        return true;
    }

    public int[] getHaves() {
        return fileManager.getHaves();
    }

    /**
     * Informs the tracker that the new peers have been taken.
     */
    public void takenNewPeers() {
        tracker.takenNewPeers();
    }

    /** 
     * Asks the file manager how many bytes of data have been downloaded.
     * @return int
     */
    public int getDownloaded() {
        return fileManager.getDownloaded();
    }

    /** 
     * Asks the file manager how many bytes of data have been uploaded.
     * @return int
     */
    public int getUploaded() {
        return fileManager.getUploaded();
    }

    /** 
     * @return String
     */
    public String getPeerId() {
        return torrentManager.getPeerId();
    }
    
    /** 
     * @return int
     */
    public int getPort() {
        return torrentManager.getPort();
    }

    /** 
     * @return infoHash hex string from the metainfo object.
     */
    public String getHexInfoHash() {
        return metainfo.getHexInfoHash();
    }

    /** 
     * @return infoHash byte[] from the metainfo object.
     */
    public byte[] getInfoHash() {
        return metainfo.getInfoHash();
    }

    /** 
     * @return a long indicating the piece length in bytes.
     */
    public long getPieceLength() {
        return metainfo.getPieceLength();
    }
    
    /** 
     * Orders the file manager to write the newly received
     * piece to the file.
     * @param req: request object of the received piece
     */
    public void receivedPiece(Request req) {
        fileManager.receivedPiece(req);
    }
    
    /** 
     * Order the file manager to update the request object
     * with the needed information from the file.
     * @param req: Request object of the peer request.
     */
    public void fillOutPiece(Request req) {
        fileManager.fillOutPiece(req);
    }

    /** 
     * @return an int indicating the number of bytes needed to represnt a bitfield.
     */
    public int getBitfieldLength() {
        return (int) ((metainfo.getPieces().length/20)/8 + 1);
    }

    public byte[] getPieces() {
        return metainfo.getPieces();
    }

    /** 
     * @return a byte[] bitfield from the file manager.
     */
    public byte[] getBitfield() {
        return fileManager.getBitfield();
    }
    
    /** 
     * Adds a peer to the peer manager that was received from the
     * peer server.
     * @param peer
     */
    public void addPeer(Peer peer) {
        peerManager.addPeer(peer);
    }
    
    /** 
     * Graciously shuts down the Torrent object. It also orders
     * shutdowns to the tracker, peermanager, filemanage.
     * @throws InterruptedException
     */
    public void shutdown() throws InterruptedException {
        log.info("Shutting down torrent " + getHexInfoHash());
        tracker.shutdown();
        peerManager.shutdown();
        fileManager.shutdown();
        tracker.join();
        peerManager.join();
    }
}