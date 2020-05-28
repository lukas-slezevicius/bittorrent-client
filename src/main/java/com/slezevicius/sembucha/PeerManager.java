package com.slezevicius.sembucha;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class for managing the peers of a particular torrent. It deals
 * with the creation, destruction, and commanding of all the peers
 * that belong to a particular torrent.
 */
public class PeerManager extends Thread {
    private final int BLOCKSIZE = 16384; //2^14
    private final int MAXPEERS = 20;
    private final int MAXPIECES = 10;
    private Torrent tor;
    private Set<Peer> peers;
    private Set<Peer> potentialBitfieldPeers;
    private Set<Peer> peersWithoutDownloads;
    private byte[] frequencyArray;
    private List<Set<Integer>> rarenessList;
    private long lastPieceSize;
    //private Map<Integer, Pair<Integer, Instant>> requestedPieces;
    private Map<Integer, Triplet<Integer, Peer, Instant>> requestedPieces;
    private Set<Integer> downloadedPieceSet;
    private boolean keepRunning = true;
    private Logger log;

    PeerManager(Torrent tor) {
        log = LogManager.getFormatterLogger(PeerManager.class);
        peers = new HashSet<>();
        potentialBitfieldPeers = new HashSet<>();
        peersWithoutDownloads = new HashSet<>();
        log.debug("%d", tor.getPieces().length);
        frequencyArray = new byte[tor.getPieces().length/20];
        rarenessList = new ArrayList<>(MAXPEERS);
        downloadedPieceSet = new HashSet<>();
        requestedPieces = new HashMap<>();
        lastPieceSize = tor.getLength()%tor.getPieceLength();
        if (lastPieceSize == 0) {
            lastPieceSize = tor.getPieceLength();
        }
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
                    requestedPieces.remove(haves[i]);
                    downloadedPieceSet.add(haves[i]);
                }
            }
            synchronized(this) {
                Iterator<Peer> it = peers.iterator();
                while (it.hasNext()) {
                    Peer peer = it.next();
                    if (!peer.isAlive()) {
                        removePeer(peer, it);
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
    
    private void removePeer(Peer peer, Iterator<Peer> it) {
        if (!potentialBitfieldPeers.contains(peer) || !peersWithoutDownloads.contains(peer)) {
            byte[] bitfield = peer.getPeerBitfield();
            for (int i = 0; i < bitfield.length; i++) {
                for (int j = 0; j < 8; j++) {
                    if (((bitfield[i] >> (7-j)) & 0x01) == 1) {
                        int oldVal = frequencyArray[i*8 + j];
                        if (oldVal > 0) {
                            if (!downloadedPieceSet.contains(i*8 + j) && !requestedPieces.containsKey(i*8 + j)) {
                                if (oldVal > rarenessList.size()) {
                                    log.warn("%s the frequencyArray val is higher than rarenessList size");
                                    rarenessList.get(rarenessList.size()-1).remove(i*8 + j);
                                    frequencyArray[i*8 + j] = (byte) peers.size();
                                } else {
                                    rarenessList.get(oldVal-1).remove(i*8 + j);
                                }
                                if (oldVal > 1) {
                                    rarenessList.get(oldVal - 2).add(i*8 + j);
                                }
                            }
                        frequencyArray[i*8 + j] -= 1;
                        }
                    }
                }
            }
        }
        log.debug("%s; Removing %s", toString(), peer.toString());
        it.remove();
        potentialBitfieldPeers.remove(peer);
        peersWithoutDownloads.remove(peer);
    }

    /**
     * Removes a requested piece if a timeout of 1min has passed.
     * If the available piece was removed, it gets added back.
     */
    private synchronized void updateRequestTimeouts() {
        for (Iterator<Map.Entry<Integer, Triplet<Integer, Peer, Instant>>> it = requestedPieces.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Triplet<Integer, Peer, Instant>> req = it.next();
            if (!Instant.now().isBefore(req.getValue().getRight().plusSeconds(60))) {
                log.debug("%s piece timed out at index %d", toString(), req.getKey().intValue());
                it.remove();
                if (!downloadedPieceSet.contains(req.getKey())) {
                    rarenessList.get(frequencyArray[req.getKey()]-1).add(req.getKey());
                    tor.timedOutPiece(req.getKey());
                    for (Peer peer : peers) {
                        peer.resetRequestCount(); //Needed because we do not know which peer requested which pieces.
                    }
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
        log.debug("%s bitfield size is %d", toString(), bitfield.length);
        for (int i = 0; i < bitfield.length; i++) {
            for (int j = 0; j < 8; j++) {
                if (i*8 + j >= frequencyArray.length) {
                    log.debug("%s Ignoring unneeded bitfield positions", toString());
                    continue;
                }
                if (((bitfield[i] >> (7-j)) & 0x01) == 1) {
                    int oldVal = frequencyArray[i*8 + j];
                    if (!downloadedPieceSet.contains(i*8 + j) && !requestedPieces.containsKey(i*8 + j)) {
                        if (oldVal > 0) {
                            rarenessList.get(oldVal-1).remove(i*8 + j);
                        }
                        if (oldVal < rarenessList.size()) {
                            rarenessList.get(oldVal).add(i*8 + j);
                        } else {
                            log.warn("the frequency array value is higher than the number of peers");
                        }
                    }
                    frequencyArray[i*8 + j] += 1;
                }
            }
        }
        peersWithoutDownloads.remove(peer);
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
                        rarenessList.add(new HashSet<Integer>());
                        peers.add(newPeer);
                        potentialBitfieldPeers.add(newPeer);
                        peersWithoutDownloads.add(newPeer);
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
        log.debug("%s finished adding peers", toString());
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
        if (peer.getAmInterested() && !peer.getPeerChocking() && !peersWithoutDownloads.contains(peer)) {
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
            peer.sendMessage(new Pair<String, ArrayList<Object>>("have", arguments));
        }
        boolean newPieceToDownload = false;
        while (true) {
            Integer idx = peer.getPeerHaves();
            if (idx == null) {
                break;
            } else if (idx >= tor.getPieces().length || idx < 0) {
                log.debug("%s received an out of bounds index", toString());
                continue;
            }
            log.debug("%s increasing frequencyArray at %d by 1 to %d", toString(), idx, frequencyArray[idx] + 1);
            int oldVal = frequencyArray[idx];
            if (!downloadedPieceSet.contains(idx) && !requestedPieces.containsKey(idx)) {
                if (oldVal > 0 && rarenessList.get(oldVal-1).contains(idx)) {
                    newPieceToDownload = true;
                    rarenessList.get(oldVal - 1).remove(idx);
                }
                if (oldVal < rarenessList.size()) {
                    rarenessList.get(oldVal).add(idx);
                } else {
                    log.warn("the frequency array value is higher than the number of peers");
                }
            }
            frequencyArray[idx] += 1;
        }
        if (newPieceToDownload) {
            peersWithoutDownloads.remove(peer);
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
                Triplet<Integer, Peer, Instant> oldReq = requestedPieces.get(Integer.valueOf(piece.index));
                if (oldReq == null) {
                    log.warn("%s received a piece %d which was not requested", toString(), piece.index);
                    continue;
                } else if (downloadedPieceSet.contains(piece.index)) {
                    log.warn("%s received a piece %d which has already been written", toString(), piece.index);
                    continue;
                } else if (piece.block == null) {
                    log.warn("%s received a piece %d with null block", toString(), piece.index);
                    continue;
                } else if (oldReq.getMiddle() != peer) {
                    log.warn("%s received a piece %d from a wrong peer", toString(), piece.index);
                    continue;
                }
                tor.receivedPiece(piece);
                Integer begin = oldReq.getLeft();
                Triplet<Integer, Peer, Instant> req = new Triplet<>(begin, peer, Instant.now());
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
            peer.sendMessage(new Pair<String, ArrayList<Object>>("interested", null));
        } else {
            peer.sendMessage(new Pair<String, ArrayList<Object>>("not interested", null));
        }
    }

    /**
     * Orders the peer to update the status of its choke.
     * @param peer
     * @param chocking
     */
    private void updateChoke(Peer peer, boolean chocking) {
        if (chocking) {
            peer.sendMessage(new Pair<String, ArrayList<Object>>("choke", null));
        } else {
            peer.sendMessage(new Pair<String, ArrayList<Object>>("unchoke", null));
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
            peer.sendMessage(new Pair<String, ArrayList<Object>>("piece", arguments));
        }
    }
    
    /** 
     * Sends a new request order to a peer.
     * @param peer
     */
    private void updateRequests(Peer peer) {
        int requestCount = peer.getRequestCount();
        if (requestCount >= MAXPIECES) {
            log.debug("%s; %s already has requested %d pieces", toString(), peer.toString(), MAXPIECES);
            return;
        }
        int pieceLength = (int) tor.getPieceLength();
        Integer reqIndex = getRandomRequestIndex(peer);
        if (reqIndex == null) {
            return;
        }
        Triplet<Integer, Peer, Instant> requestedPiece = requestedPieces.get(reqIndex);
        Integer begin;
        if (requestedPiece != null) {
            begin = requestedPiece.getLeft();
        } else {
            log.debug("%s removing %d from rarenessList", toString(), reqIndex);
            rarenessList.get(frequencyArray[reqIndex] - 1).remove(reqIndex);
            begin = 0;
        }
        int length;
        boolean last = false;
        for (int i = 0; i < 10 - requestCount; i++) {
            if (begin + BLOCKSIZE >= pieceLength
                && (reqIndex + 1 == tor.getPieces().length/20
                || frequencyArray[reqIndex + 1] == 0 
                || downloadedPieceSet.contains(reqIndex + 1)
                || requestedPieces.get(reqIndex + 1) != null))
            {
                length = pieceLength - begin;
                last = true;
            } else if ((reqIndex + 1 == tor.getPieces().length/20) && begin + BLOCKSIZE >= lastPieceSize) {
                length = (int) (lastPieceSize - begin);
                last = true;
            } else {
                length = BLOCKSIZE;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(reqIndex);
            arguments.add(begin);
            arguments.add(length);
            log.debug("%s requesting block with index %d, begin %d, length %d for %s", toString(), reqIndex, begin, length, peer.toString());
            peer.sendMessage(new Pair<String, ArrayList<Object>>("request", arguments));
            peer.incRequestCount();
            begin += BLOCKSIZE;
            if (begin >= pieceLength || last) {
                log.debug("%s finalizing request of piece at %d", toString(), reqIndex);
                Triplet<Integer, Peer, Instant> req = new Triplet<>(-1, peer, Instant.now());
                requestedPieces.put(reqIndex, req);
                if (i == 10 - requestCount - 1) {
                    return;
                }
                if (last) {
                    reqIndex = getRandomRequestIndex(peer);
                    if (reqIndex == null) {
                        return;
                    }
                    requestedPiece = requestedPieces.get(reqIndex);
                    if (requestedPiece != null) {
                        begin = requestedPiece.getLeft();
                    } else {
                        log.debug("%s removing %d from rarenessList", toString(), reqIndex);
                        rarenessList.get(frequencyArray[reqIndex] - 1).remove(reqIndex);
                        begin = 0;
                    }
                    last = false;
                    continue;
                }
                begin -= pieceLength;
                reqIndex += 1;
                log.debug("%s removing %d from rarenessList", toString(), reqIndex);
                rarenessList.get(frequencyArray[reqIndex] - 1).remove(reqIndex);
            }
        }
        Triplet<Integer, Peer, Instant> req = new Triplet<>(begin, peer, Instant.now());
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
        for (Map.Entry<Integer, Triplet<Integer, Peer, Instant>> entry : requestedPieces.entrySet()) {
            if (entry.getValue().getLeft() != -1 && entry.getValue().getMiddle() == peer) {
                return entry.getKey();
            }
        }
        byte[] peerBitfield = peer.getPeerBitfield();
        for (Set<Integer> rarenessLevel : rarenessList) {
            List<Integer> indexList = new ArrayList<Integer>(rarenessLevel); //This is really inefficient
            Collections.shuffle(indexList);
            for (Integer index : indexList) {
                int bitfieldIndex = index/8;
                int bitIndex = index%8;
                if (((peerBitfield[bitfieldIndex] >>> (7-bitIndex)) & 0x01) == 1) {
                    return index;
                }
            }
        }
        log.debug("%s; %s without downloads", toString(), peer.toString());
        peersWithoutDownloads.add(peer);
        return null;
    }

    /**
     * The file manager has determined that the piece at index
     * was invalid and needs to be downloaded again. Adds the 
     * index to availablePieceList and removes it from requestedPieces.
     * @param index
     */
    public void redownloadPiece(Integer index) {
        log.debug("%s redownloading piece at index %d", toString(), index.intValue());
        rarenessList.get(frequencyArray[index] - 1).add(index);
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
            peersWithoutDownloads.add(peer);
            rarenessList.add(new HashSet<Integer>());
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
        return (int) Math.ceil((double) (tor.getPieces().length/20)/8);
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