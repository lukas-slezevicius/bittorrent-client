package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PeerManager extends Thread {
    private TorrentManager torrentManager;
    private FileManager fileManager;
    private PeerServer server;
    private String peerId;
    private volatile boolean newTorrent;
    private ArrayList<String> torrents = new ArrayList<>();
    private ArrayList<TorrentPeerInfo> peers = new ArrayList<>();
    private HashMap<String, byte[]> bitfields;
    private volatile HashMap<String, byte[]> frequencyArrays;
    private HashMap<String, ArrayList<Integer>> indexArrays;
    private HashMap<String, HashMap<Integer, byte[]>> requestedPieces; //Don't forget initialization
    private Random rand;
    private final int blockSize = (int) Math.pow(2, 14);
    private volatile boolean keepRunning = true;
    private Logger log;

    PeerManager(String peerId, int port) throws IOException  {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.peerId = peerId;
        server = new PeerServer(this, port);
        rand = new Random();
    }

    public void addTorrentManager(TorrentManager torrentManager) {
        this.torrentManager = torrentManager;
        this.fileManager = new FileManager(this, torrentManager);
    }

    @Override
    public void run() {
        Instant startTime = Clock.systemUTC().instant();
        while (true) {
            synchronized(this) {
                if (newTorrent || !Clock.systemUTC().instant().isBefore(startTime.plusSeconds(15*60))) {
                    updatePeers(torrentManager.getPeers());
                    startTime = Clock.systemUTC().instant();
                    newTorrent = false;
                }
            }
            for (TorrentPeerInfo peerInfo : peers) {
                updateOrder(peerInfo);
            }
            synchronized(this) {
                if (!keepRunning) {
                    shutdown();
                }
            }
        }
    }

    private void updatePeers(HashMap<String, HashSet<Pair<InetAddress, Integer>>> peers) {
        for (Map.Entry<String, HashSet<Pair<InetAddress, Integer>>> peerEntry : peers.entrySet()) {
            if (torrents.contains(peerEntry.getKey())) {
                int peerCount = 0;
                HashSet<Pair<InetAddress, Integer>> peerPairSet = new HashSet<>();
                for (TorrentPeerInfo peer : this.peers) {
                    if (peer.hexInfoHash == peerEntry.getKey()) {
                        peerCount += 1;
                        peerPairSet.add(peer.getPeerPair());
                    }
                }
                for (Pair<InetAddress, Integer> pair : peerEntry.getValue()) {
                    if (!peerPairSet.contains(pair) && peerPairSet.size() < 50) {
                        Peer peer = new Peer(pair, this);
                        this.peers.add(new TorrentPeerInfo(peerEntry.getKey(), peer));
                    }
                }
            } else {
                torrents.add(peerEntry.getKey());
                for (Pair<InetAddress, Integer> pair : peerEntry.getValue()) {
                    Peer peer = new Peer(pair, this));
                    this.peers.add(new TorrentPeerInfo(peerEntry.getKey(), peer));
                }
            }
        }
    }

    private void updateOrder(TorrentPeerInfo peerInfo) {
        //Make a global way of sending HAVEs
        //Create an endgame system with cancellations
        if (peerInfo.peer.getPeerInterested() && !peerInfo.peer.getAmChocking()) {
            updatePiece(peerInfo);
        }
        if (peerInfo.peer.getAmInterested() && !peerInfo.peer.getPeerChocking()) {
            updateRequests(peerInfo);
        }
    }

    private void updatePiece(TorrentPeerInfo peerInfo) {
        Request req = peerInfo.peer.getRequest();
        if (req != null) {
            int pieceLength = torrentManager.getTorrent(peerInfo.hexInfoHash).getMetainfo().getPieceLength();
            int pieceCount = (int) Math.ceil(req.block.length/pieceLength);
            byte[] bitfield = bitfields.get(peerInfo.hexInfoHash);
            if (Math.ceil(bitfield.length/pieceLength) <= req.index + pieceCount) {
                log.finer("Invalid index/length of request");
                return;
            }
            int bitfieldIndex = req.index * pieceLength + req.begin;
            for (int i = 0; i < req.block.length; i++) {
                req.block[i] = bitfield[bitfieldIndex];
                bitfieldIndex += 1;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(req);
            peerInfo.peer.addOrder(new Pair<String, ArrayList<Object>>("piece", arguments));
        }
    }

    private void updateRequests(TorrentPeerInfo peerInfo) {
        if (peerInfo.requestCount >= 10) {
            return;
        }
        int pieceLength = torrentManager.getTorrent(peerInfo.hexInfoHash).getMetainfo().getPieceLength();
        ArrayList<Integer> indexArr = indexArrays.get(peerInfo.hexInfoHash);
        while (true) {
            int reqIndex = rand.nextInt((int) Math.ceil(indexArr.size() * 0.2));
            if (requestedPieces.get(peerInfo.hexInfoHash).containsKey(reqIndex)) {
                continue;
            }
            boolean EOF = false;
            int begin = 0;
            int length;
            while (peerInfo.requestCount != 10) {
                if (begin + blockSize >= pieceLength && reqIndex + 1 == frequencyArrays.get(peerInfo.hexInfoHash).length) {
                    length = pieceLength - begin;
                    requestedPieces.get(peerInfo.hexInfoHash).put(reqIndex, new byte[pieceLength]);
                    EOF = true;
                } else {
                    length = blockSize;
                }
                ArrayList<Object> arguments = new ArrayList<>();
                arguments.add(reqIndex);
                arguments.add(begin);
                arguments.add(length);
                peerInfo.peer.addOrder(new Pair<String, ArrayList<Object>>("request", arguments));
                begin += blockSize;
                if (EOF) {
                    return;
                } else if (begin >= pieceLength) {
                    begin -= pieceLength;
                    reqIndex += 1;
                }
            }
        }
    }

    private ArrayList<Integer> getSortedArrayIndices(byte[] arr) {
        arr = Arrays.copyOf(arr, arr.length);
        Integer[] indices = new Integer[arr.length];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        int n = arr.length;
        boolean swapped;
        do {
            swapped = false;
            for (int i = 1; i < n - 1; i++) {
                if (arr[i - 1] > arr[i]) {
                    byte tempArr = arr[i -1];
                    int tempIndex = indices[i - 1];
                    peerInfo peerInfo                   arr[i] = tempArr;
                    indices[i] = tempIndex;
                    swapped = true;
                }
            }
            n -= 1;
        } while(swapped);
        return new ArrayList<Integer>(Arrays.asList(indices));
    }


    public Torrent getTorrent(byte[] infoHash) {
    }

    private void updatePiece(int idx, int begin, byte[] block) {

    }

    public String getPeerId() {
        return peerId;
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

    public FileManager getFileManager() {
        return fileManager;
    }

    public synchronized void checkTorrent() {
        newTorrent = true;
    }

    public synchronized void stopRunning() {
        keepRunning = false;
    }

    private void shutdown() {

    }
}