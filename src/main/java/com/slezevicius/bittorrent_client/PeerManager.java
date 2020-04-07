package com.slezevicius.bittorrent_client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PeerManager extends Thread {
    private TorrentManager torrentManager;
    private byte[] bitfield;

    private volatile ConcurrentLinkedQueue<ArrayList<Object>> pieceQueue;
    private HashSet<Peer> peers;
    private volatile boolean keepRunning = true;
    private Logger log;

    /*
    PeerManager(int bitfieldLength) {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        bitfield = new byte[bitfieldLength];
        pieceQueue = new ConcurrentLinkedQueue<>();
        peers = new HashSet<Peer>();
    }

    PeerManager(byte[] bitfield) {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.bitfield = bitfield;
        pieceQueue = new ConcurrentLinkedQueue<>();
        peers = new HashSet<Peer>();
    }
    */
    PeerManager(TorrentManager torrentManager) {
        this.torrentManager = torrentManager;
    }

    @Override
    public void run() {
        while (true) {
            synchronized(peers) {
                for (Peer peer : peers) {
                    Pair<String, ArrayList<Object>> order = nextOrder(peer);
                    if (order != null) {
                        peer.getOrderQueue().add(order);
                    }
                }
            }
            while (!pieceQueue.isEmpty()) {
                ArrayList<Object> piece = pieceQueue.poll();
                updatePiece((int) piece.get(0), (int) piece.get(1), (byte[]) piece.get(2));
            }
            synchronized(this) {
                if (!keepRunning) {
                    return;
                }
            }
        }
    }

    public Torrent getTorrent(byte[] infoHash) {

    }

    private void updatePiece(int idx, int begin, byte[] block) {

    }

    private Pair<String, ArrayList<Object>> nextOrder(Peer peer) {
        return null;
    }

    public void addPeer(Peer peer) {
        synchronized(peers) {
            peers.add(peer);
        }
    }

    public ConcurrentLinkedQueue<ArrayList<Object>> getPieceQueue() {
        return pieceQueue;
    }

    public byte[] getBitfield() {
        return bitfield;
    }

    public void receivedRequest(Peer peer, int idx, int begin, int length) {
    }

    public void receivedCancel(Peer peer, int idx, int begin, int length) {
    }

    public synchronized void stopRunning() {
        keepRunning = false;
    }
}