package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class for managing the peers of a particular torrent. It deals
 * with the creation, destruction, and commanding of all the peers
 * that belong to a particular torrent.
 */
public class PeerManager extends Thread {
    private final int BLOCKSIZE = 16384; //2^14
    private final int MAXPEERS = 15;
    private final int MAXPIECES = 10;
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
    private List<Integer> unfinishedRequestedPieceList;
    private List<Integer> downloadedPieceList;

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
        log = LogManager.getFormatterLogger(PeerManager.class);
        peers = new ArrayList<>();
        potentialBitfieldPeers = new ArrayList<>();
        //frequencyArray = new byte[tor.getPieces().length];
        availablePieceList = new ArrayList<>();
        unfinishedRequestedPieceList = new ArrayList<>();
        downloadedPieceList = new ArrayList<>();
        requestedPieces = new HashMap<>();
        rand = new Random();
        this.tor = tor;
        log.trace("%s initialized", toString());
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
        log.trace("%s in the main loop", toString());
        Instant timeSinceNoPeers = Instant.now();
        while (true) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                log.error(toString(), e);
                return;
            }
            synchronized(this) {
                if (!keepRunning) {
                    break;
                }
            }
            if (tor.isComplete()) {
                log.info("%s downloaded file");
                //Temporary while not including seeding
                return;
            }
            if (tor.newPeers()) {
                log.debug("%s updating peers", toString());
                updatePeers();
                tor.takenNewPeers();
            }
            if (peers.size() == 0 && Instant.now().isAfter(timeSinceNoPeers.plusSeconds(60))) {
                log.debug("%s requesting a new request to the tracker", toString());
                tor.updateTracker();
                timeSinceNoPeers = Instant.now();
            } else if (peers.size() > 0) {
                timeSinceNoPeers = Instant.now();
            }

            updateRequestTimeouts();
            int[] haves = tor.getHaves();
            for (int i = 0; i < haves.length; i++) {
                log.debug("%s downloaded piece at index %d", toString(), haves[i]);
                synchronized(this) {
                    availablePieceList.remove(Integer.valueOf(haves[i])); //Needed because, potentially an unrequested block can pass through a request which has not been fully requested
                    requestedPieces.remove(Integer.valueOf(haves[i]));
                    downloadedPieceList.add(Integer.valueOf(haves[i]));
                }
            }
            synchronized(this) {
                Iterator<Peer> it = peers.iterator();
                while (it.hasNext()) {
                    Peer peer = it.next();
                    if (!peer.isAlive()) {
                        log.debug("%s; Removing %s", toString(), peer.toString());
                        it.remove();
                        if (potentialBitfieldPeers.contains(peer)) {
                            potentialBitfieldPeers.remove(peer);
                        }
                        continue;
                    }
                    if (potentialBitfieldPeers.contains(peer)) {
                        if (peer.hasReceivedBitfield()) {
                            log.debug("%s getting bitfield from %s", toString(), peer.toString());
                            updateBitfield(peer);
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
    private synchronized void updateRequestTimeouts() {
        for (Iterator<Map.Entry<Integer, Pair<Integer, Instant>>> it = requestedPieces.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Pair<Integer, Instant>> req = it.next();
            if (!Instant.now().isBefore(req.getValue().getRight().plusSeconds(60))) {
                log.debug("%s piece timed out at index %d", toString(), req.getKey().intValue());
                it.remove();
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
                if (((bitfield[i] >> j) & 0x01) == 1 && !downloadedPieceList.contains(i*8 + j) && !availablePieceList.contains(i*8 + j)) {
                    availablePieceList.add(i*8 + j);
                }
            }
        }
    }

    /**
     * Updates the peer list with any new peers. Keeps the peer
     * count to 50 as a maximum and does not allow any duplicates.
     */
    private synchronized void updatePeers() {
        List<Pair<InetAddress, Integer>> newPeers = tor.getNewPeers();
        for (Pair<InetAddress, Integer> pair : newPeers) {
            if (peers.size() < MAXPEERS) {
                boolean peerExists = false;
                for (Peer peer : peers) {
                    if (peer.getNetworkPair().equals(pair)) {
                        log.debug("%s; %s is already in the peer list", toString(), peer.toString());
                        peerExists = true;
                        break;
                    }
                }
                if (!peerExists) {
                    try {
                        log.debug("%s connecting to new peer[ip=%s, port=%d]", toString(), pair.getLeft().toString(), pair.getRight().intValue());
                        Peer newPeer = new Peer(pair, this);
                        newPeer.start();
                        peers.add(newPeer);
                        potentialBitfieldPeers.add(newPeer);
                    } catch (IOException e) {
                        log.error("%s could not connect to the new peer[ip=%s, port=%d]", toString(), pair.getLeft().toString(), pair.getRight().intValue());
                        log.error(e.getMessage(), e);
                        continue;
                    }
                }
            } else {
                log.debug("%s Already got %d peers", toString(), MAXPEERS);
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
        //If fully downloaded don't update requests
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
            log.debug("%s adding have order at index %d", toString(), haves[i]);
            peer.addOrder(new Pair<String, ArrayList<Object>>("have", arguments));
            requestedPieces.remove(Integer.valueOf(haves[i]));
        }
        while (true) {
            Integer idx = peer.getPeerHaves();
            if (idx == null) {
                break;
            } else if (idx >= tor.getPieces().length || idx < 0) {
                log.debug("%s received an out of bounds index", toString());
                continue;
            }
            log.debug("%s increasing frequencyArray at %d by 1", toString(), idx);
            //frequencyArray[idx] += 1; //Needed only later when decision strategy will be implemented
            if (!downloadedPieceList.contains(idx) && !availablePieceList.contains(idx)) {
                log.debug("%s adding %d to available piece list", toString(), idx);
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
                log.debug("%s received piece at index %d, begin %d, length %d from %s", toString(), piece.index, piece.begin, piece.block.length, peer.toString());
                Pair<Integer, Instant> oldReq = requestedPieces.get(Integer.valueOf(piece.index));
                if (oldReq == null) {
                    log.warn("%s received a piece %d which was not requested", toString(), piece.index);
                    continue;
                } else if (downloadedPieceList.contains(piece.index)) {
                    log.warn("%s received a piece %d which has already been downloaded", toString(), piece.index);
                    continue;
                }
                tor.receivedPiece(piece);
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
                log.warn("%s received a request with unacceptable length", toString());
                return;
            }
            tor.fillOutPiece(req);
            if (req.block == null) {
                log.debug("%s did not have the requested block", toString());
                return;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(req);
            log.debug("%s sending piece with idx %d, begin %d, length %d", toString(), req.index, req.begin, req.block.length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("piece", arguments));
        }
    }
    
    /** 
     * Sends a new request order to a peer.
     * @param peer
     */
    private void updateRequests(Peer peer) {
        //How will you check if that specific peer has the piece?
        int requestCount = peer.getRequestCount();
        if (requestCount >= MAXPIECES) {
            log.debug("%s; %s already has requested %d pieces", toString(), peer.toString(), MAXPIECES);
            return;
        }
        int pieceLength = (int) tor.getPieceLength();
        Integer reqIndex = getRandomRequestIndex(peer);
        Pair<Integer, Instant> requestedPiece = requestedPieces.get(reqIndex);
        Integer begin;
        //Check against peer bitfield here
        if (requestedPiece != null) {
            begin = requestedPiece.getLeft();
        } else {
            unfinishedRequestedPieceList.add(reqIndex);
            availablePieceList.remove(reqIndex);
            begin = 0;
        }
        int length;
        boolean last = false;
        for (int i = 0; i < 10 - requestCount; i++) {
            if (begin + BLOCKSIZE >= pieceLength && (reqIndex + 1 == tor.getPieces().length || !availablePieceList.contains(reqIndex + 1))) {
                length = pieceLength - begin;
                last = true;
            } else {
                length = BLOCKSIZE;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(reqIndex);
            arguments.add(begin);
            arguments.add(length);
            log.debug("%s requesting block with index %d, begin %d, length %d", toString(), reqIndex, begin, length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("request", arguments));
            peer.incRequestCount();
            begin += BLOCKSIZE;
            if (begin >= pieceLength) {
                Pair<Integer, Instant> req = new Pair<Integer, Instant>(-1, Instant.now());
                requestedPieces.put(reqIndex, req);
                availablePieceList.remove(reqIndex);
                unfinishedRequestedPieceList.remove(reqIndex);
                if (last) {
                    reqIndex = getRandomRequestIndex(peer);
                    requestedPiece = requestedPieces.get(reqIndex);
                    if (requestedPiece != null) {
                        begin = requestedPiece.getLeft();
                    } else {
                        unfinishedRequestedPieceList.add(reqIndex);
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
    private Integer getRandomRequestIndex(Peer peer) {
        for (Integer unfinishedPieceIndex : unfinishedRequestedPieceList) {
            int bitfieldIndex = unfinishedPieceIndex/8;
            int bitIndex = unfinishedPieceIndex%8;
            if (((peer.getPeerBitfield()[bitfieldIndex] >> bitIndex) & 0x01) == 1) {
                return unfinishedPieceIndex;
            }
        }
        int availablePieceIndex = rand.nextInt(availablePieceList.size());
        return availablePieceList.get(availablePieceIndex);
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
        log.debug("%s redownloading piece at index %d", toString(), index.intValue());
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
            log.debug("Adding new %s to the peer list", peer.toString());
            peer.introducePeerManager(this);
            peer.start();
        synchronized(this) {
            peers.add(peer);
            potentialBitfieldPeers.add(peer);
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

    public String getFileName() {
        return tor.getName();
    }

    /**
     * Graciously shuts down the peer manager by
     * ordering all peers to shutdown and eventually
     * shutting itself down.
     */
    public synchronized void shutdown() {
        log.trace("shutting down %s", toString());
        keepRunning = false;
        for (Peer peer : peers) {
            peer.close();
        }
        for (Peer peer : peers) {
            try {
                peer.join();
            } catch (InterruptedException e) {
                log.error("%s interrupted while joining peer", toString());
                log.error(e.getMessage(), e);
            }
        }
        log.trace("%s successfuly shut down", toString());
    }

    @Override
    public String toString() {
        return String.format("PeerManager[name=%s]", tor.getName());
    }
}