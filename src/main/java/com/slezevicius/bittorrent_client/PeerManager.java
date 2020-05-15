package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A class for managing the peers of a particular torrent. It deals
 * with the creation, destruction, and commanding of all the peers
 * that belong to a particular torrent.
 */
public class PeerManager extends Thread {
    private final int BLOCKSIZE = 16384; //2^14
    private Torrent tor;
    private List<Peer> peers;
    private List<Peer> potentialBitfieldPeers;

    /**
     * An array where the ith byte represent the frequency of the ith piece
     */
    //private byte[] frequencyArray;

    /**
     * List of pieces that are available for downloading.
     */
    private List<Integer> availablePieceList;

    /**
     * An array list where the ith integer contains the index of the ith rarest (lowest frequency)
     * piece. It is a list because some of the indices can get removed whenever a piece is fully
     * downloaded.
     */
    //private ArrayList<Integer> rarestFirstList;

    /**
     * Hashmap where the the key is an index of a requested piece and the value
     * has an index i if no blocks have been requested from i to pieceLength - 1 within
     * the piece. If the piece is fully requested, the value will be -1 .
     */
    private Map<Integer, Pair<Integer, Instant>> requestedPieces;
    private boolean keepRunning = true;
    private Random rand;
    private Logger log;

    PeerManager(Torrent tor) {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        peers = new ArrayList<>();
        potentialBitfieldPeers = new ArrayList<>();
        //frequencyArray = new byte[tor.getPieces().length];
        availablePieceList = new ArrayList<>();
        requestedPieces = new HashMap<>();
        rand = new Random();
        this.tor = tor;
    }

    PeerManager() {
        //Empty constructor for testing
    }

    /**
     * The main loop for the peer manager's thread. It keeps
     * checking whether any new peers have been added by the
     * tracker and issues out orders to the peers.
     */
    @Override
    public void run() {
        while (true) {
            synchronized(this) { //Is such a big critical region needed
                if (!keepRunning) {
                    break;
                }
                if (tor.newPeers()) {
                    updatePeers();
                    tor.takenNewPeers();
                }
                updateRequestTimeouts();
                int[] haves = tor.getHaves();
                for (int i = 0; i < haves.length; i++) {
                    //Fully downloaded pieces
                    availablePieceList.remove(Integer.valueOf(haves[i]));
                    requestedPieces.remove(Integer.valueOf(haves[i]));
                }
                for (Peer peer : peers) {
                    if (potentialBitfieldPeers.contains(peer)) {
                        if (peer.hasReceivedBitfield()) {
                            updateBitfield(peer);
                        }
                        if (peer.hasReceivedFirstMessage()) {
                            potentialBitfieldPeers.remove(peer);
                        }
                    }
                    updateOrder(peer, haves);
                }
            }
        }
    }

    /**
     * Removes a requested piece if a timeout of 1min has passed.
     * If the available piece was removed, it gets added back.
     */
    private void updateRequestTimeouts() {
        for (Map.Entry<Integer, Pair<Integer, Instant>> req : requestedPieces.entrySet()) {
            if (!Instant.now().isBefore(req.getValue().getRight().plusSeconds(60))) {
                requestedPieces.remove(req.getKey());
                if (!availablePieceList.contains(req.getKey())) {
                    availablePieceList.add(req.getKey());
                }
            }
        }
    }

    /**
     * Updates the availablePieceList with the bitfield of a given
     * peer.
     * @param peer
     */
    private void updateBitfield(Peer peer) {
        byte[] bitfield = peer.getPeerBitfield();
        for (int i = 0; i < bitfield.length; i++) {
            for (int j = 0; j < 8; j++) {
                if (((bitfield[i] >> j) & 0x01) == 1 && !availablePieceList.contains(Integer.valueOf(i*8 + j))) {
                    availablePieceList.add(i*8 + j);
                }
            }
        }
    }

    /**
     * Updates the peer list with any new peers. Keeps the peer
     * count to 50 as a maximum and does not allow any duplicates.
     */
    private void updatePeers() {
        List<Pair<InetAddress, Integer>> newPeers = tor.getNewPeers();
        for (Pair<InetAddress, Integer> pair : newPeers) {
            if (peers.size() < 50) {
                boolean peerExists = false;
                for (Peer peer : peers) {
                    if (peer.getNetworkPair().equals(pair)) {
                        log.finest("Peer already exists");
                        peerExists = true;
                        break;
                    }
                }
                if (!peerExists) {
                    try {
                        Peer newPeer = new Peer(pair, this);
                        newPeer.start();
                        peers.add(newPeer);
                        potentialBitfieldPeers.add(newPeer);
                    } catch (IOException e) {
                        log.warning("Could not connect to peer with ip " + pair.getLeft() + " and port " + pair.getRight());
                        continue;
                    }
                }
            } else {
                log.fine("Already got 50 peers");
                break;
            }
        }
    }
    
    /** 
     * Issues out new orders to every peer.
     * @param peer
     */
    private void updateOrder(Peer peer, int[] haves) {
        //Don't forget about sending bitfields, check in tests what's expected
        //Make sure that the peer does not accept pieces that have not been requested
        //Create an endagme system with cancellation
        updateHaves(peer, haves); //Don't forget to update the rarest list
        updateReceivedPieces(peer);
        if (tor.isDownloading() && !peer.getAmInterested()) {
            updateInterest(peer, true);
            return;
        } else if (!tor.isDownloading() && peer.getAmInterested()) {
            updateInterest(peer, false);
        }
        if (tor.isUploading() && peer.getAmChocking()) {
            updateChoke(peer, false);
        } else if (!tor.isUploading() && !peer.getAmChocking()) {
            updateChoke(peer, true);
        }
        if (peer.getPeerInterested() && !peer.getAmChocking()) {
            updatePeerPiece(peer);
        }
        if (peer.getAmInterested() && !peer.getPeerChocking()) {
            updateRequests(peer);
            // if (availablePieceList.size()/tor.getPieces().length > 0.05) {
            //     updateRequests(peer);
            // } else {
            //     updateFinalRequests(peer);
            // }
        }
    }

    /**
     * Sends an order to send a have message to the peer.
     * @param peer
     * @param haves: haves int[] received from the file manager
     */
    private void updateHaves(Peer peer, int[] haves) {
        for (int i = 0; i < haves.length; i++) {
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(haves[i]);
            log.finest("Adding have order: " + haves[i]);
            peer.addOrder(new Pair<String, ArrayList<Object>>("have", arguments));
        }
        while (true) {
            Integer idx = peer.getPeerHaves();
            if (idx == null) {
                break;
            } else if (idx >= tor.getPieces().length) {
                log.fine("Received an index from the peer which is too large.");
                continue;
            } else if (idx < 0) {
                log.warning("Received an index below 0 from the peer");
                continue;
            }
            log.finest("Increading frequencyArray at " + idx + " by 1");
            //frequencyArray[idx] += 1; //Needed only later when decision strategy will be implemented
            if (!availablePieceList.contains(idx)) {
                log.finest("Adding " + idx + " to available piece list");
                availablePieceList.add(idx);
            }
        }
    }

    /**
     * Updates the file manager with any new pieces that have been
     * received from the peer. The timeout of the request gets updated.
     * @param peer
     */
    private void updateReceivedPieces(Peer peer) {
        while (true) {
            Request piece = peer.getNewPiece();
            if (piece != null) {
                tor.receivedPiece(piece);
                Pair<Integer, Instant> oldReq = requestedPieces.get(Integer.valueOf(piece.index));
                if (oldReq == null) {
                    log.warning("Received a piece which was not requested");
                    continue;
                }
                Integer begin = oldReq.getLeft();
                Pair<Integer, Instant> req = new Pair<Integer, Instant>(begin, Instant.now());
                requestedPieces.put(Integer.valueOf(piece.index), req);
            } else {
                break;
            }
        }
    }

    /**
     * Orders the peer to update the status of its interest.
     * @param peer
     * @param interested
     */
    private void updateInterest(Peer peer, boolean interested) {
        if (interested) {
            peer.addOrder(new Pair<String, ArrayList<Object>>("interested", null));
        } else {
            peer.addOrder(new Pair<String, ArrayList<Object>>("not interested", null));
        }
    }

    /**
     * Orders the peer to update the status of its choke.
     * @param peer
     * @param chocking
     */
    private void updateChoke(Peer peer, boolean chocking) {
        if (chocking) {
            peer.addOrder(new Pair<String, ArrayList<Object>>("choke", null));
        } else {
            peer.addOrder(new Pair<String, ArrayList<Object>>("unchoke", null));
        }

    }
    
    /** 
     * Sends out one piece that was previously requested by a peer.
     * Drops a request larger than 128KB or smaller than 8KB.
     * @param peer
     */
    private void updatePeerPiece(Peer peer) {
        Request req = peer.getRequest(); //Get the latest request
        if (req != null) {
            //Check if I am willing to send a piece currently
            if (req.block.length > 131072 || req.block.length < 8192) {
                log.warning("Received a request with length " + req.block.length);
                return;
            }
            tor.fillOutPiece(req);
            if (req.block == null) {
                log.finest("Did not have the requested block");
                return;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(req);
            log.finest("Sending piece with idx " + req.index + " begin " + req.begin);
            peer.addOrder(new Pair<String, ArrayList<Object>>("piece", arguments));
        }
    }
    
    /** 
     * Sends a new request order to a peer.
     * @param peer
     */
    private void updateRequests(Peer peer) {
        int requestCount = peer.getRequestCount();
        if (requestCount >= 10) {
            return;
        }
        int pieceLength = (int) tor.getPieceLength();
        Integer reqIndex = getRandomRequestIndex();
        Integer begin = requestedPieces.get(reqIndex).getLeft();
        if (begin == null) {
            begin = 0;
        }
        int length;
        boolean last = false;
        for (int i = 0; i < 10 - requestCount; i++) {
            if (begin + BLOCKSIZE >= pieceLength && (reqIndex + 1 == tor.getPieces().length
                || (requestedPieces.containsKey(reqIndex + 1) && requestedPieces.get(reqIndex + 1).getLeft() == -1))) {
                length = pieceLength - begin;
                last = true;
            } else {
                length = BLOCKSIZE;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(reqIndex);
            arguments.add(begin);
            arguments.add(length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("request", arguments));
            peer.incRequestCount();
            begin += BLOCKSIZE;
            if (begin >= pieceLength) {
                Pair<Integer, Instant> req = new Pair<Integer, Instant>(-1, Instant.now());
                requestedPieces.put(reqIndex, req);
                availablePieceList.remove(reqIndex);
                if (last) {
                    reqIndex = getRandomRequestIndex();
                    begin = requestedPieces.get(reqIndex).getLeft();
                    if (begin == null) {
                        begin = 0;
                    }
                    last = false;
                    continue;
                }
                begin -= pieceLength;
                reqIndex += 1;
            }
        }
        Pair<Integer, Instant> req = new Pair<Integer, Instant>(begin, Instant.now());
        requestedPieces.put(reqIndex, req); //Allows to start from the same point the next time
    }

    private void updateFinalRequests(Peer peer) {
        //To be implemented later
    }
    
    /** 
     * Returns the index of a random available piece.
     * Should not be called during the final download stage.
     * @return int
     */
    private Integer getRandomRequestIndex() {
        while (true) {
            int availablePieceIndex = rand.nextInt(availablePieceList.size());
            return availablePieceList.get(availablePieceIndex);
        }
    }
    
    /** 
     * To be implemented later.
     * @param arr
     * @return ArrayList<Integer>
     */
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
                    arr[i] = tempArr;
                    indices[i] = tempIndex;
                    swapped = true;
                }
            }
            n -= 1;
        } while(swapped);
        return new ArrayList<Integer>(Arrays.asList(indices));
    }

    /**
     * To be implemented later.
     * @param b
     */
    private void updateSortedArrayIndices(byte b) {
    }

    /**
     * The file manager has determined that the piece at index
     * was invalid and needs to be downloaded again. Adds the 
     * index to availablePieceList and removes it from requestedPieces.
     * @param index
     */
    public void redownloadPiece(Integer index) {
        availablePieceList.add(index);
        requestedPieces.remove(index);
    }
    
    /** 
     * @return String
     */
    public String getPeerId() {
        return tor.getPeerId();
    }

    /**
     * @return byte[]
     */
    public byte[] getInfoHash() {
        return tor.getInfoHash();
    }
    
    /** 
     * @param peer
     */
    public void addPeer(Peer peer) {
        synchronized(this) {
            peer.introducePeerManager(this);
            peers.add(peer);
            potentialBitfieldPeers.add(peer);
            peer.start();
        }
    }
    
    /** 
     * @return byte[]
     */
    public byte[] getBitfield() {
        return tor.getBitfield();
    }

    /**
     * Returns the number of bytes needed to represent a bitfield
     * @return int
     */
    public int getBitfieldLength() {
        return (int) ((tor.getPieces().length/20)/8 + 1);
    }

    /**
     * Graciously shuts down the peer manager by
     * ordering all peers to shutdown and eventually
     * shutting itself down.
     */
    public synchronized void shutdown() {
        keepRunning = false;
        for (Peer peer : peers) {
            peer.close();
        }
        for (Peer peer : peers) {
            try {
                peer.join(); //Add the time
            } catch (InterruptedException e) {
                log.warning("Interrupted while joining peer.");
            }
        }
    }
}