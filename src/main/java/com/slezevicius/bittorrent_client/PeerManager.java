package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.InetAddress;
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

    /**
     * An array where the ith byte represent the frequency of the ith piece
     */
    private byte[] frequencyArray;

    /**
     * List of pieces that are available for downloading.
     */
    private List<Integer> availablePieceList;

    /**
     * An array list where the ith integer contains the index of the ith rarest (lowest frequency)
     * piece. It is a list because some of the indices can get removed whenever a piece is fully
     * downloaded.
     */
    private ArrayList<Integer> rarestFirstList;

    /**
     * Hashmap where the the key is an index of a requested piece and the value
     * has an index i if no blocks have been requested from i to pieceLength - 1 within
     * the piece. If the piece is fully requested, the value will be -1 .
     */
    private Map<Integer, Integer> requestedPieces;
    private Logger log;

    PeerManager(Torrent tor) {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        peers = new ArrayList<>();
        availablePieceList = new ArrayList<>();
        requestedPieces = new HashMap<>();
        this.tor = tor;
    }

    PeerManager() {
        //For testing
    }

    /**
     * The main loop for the peer manager's thread. It keeps
     * checking whether any new peers have been added by the
     * tracker and issues out orders to the peers.
     */
    @Override
    public void run() {
        //Add indices sorting logic
        while (true) {
            synchronized(this) {
                if (tor.newPeers()) {
                    updatePeers();
                    tor.takenNewPeers();
                }
                int[] haves = tor.getHaves();
                for (Peer peer : peers) {
                    updateOrder(peer, haves);
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
            if (peers.size() <= 50) {
                boolean peerExists = false;
                for (Peer peer : peers) {
                    if (peer.getNetworkPair().equals(pair)) {
                        peerExists = true;
                        break;
                    }
                }
                if (!peerExists) {
                    try {
                        Peer newPeer = new Peer(pair, this);
                        newPeer.start();
                        peers.add(newPeer);
                    } catch (IOException e) {
                        continue;
                    }
                }
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
        }
    }

    /**
     * Sends an order to send a have message to the peer.
     * @param peer
     * @param haves: haves int[] received from the file manager
     */
    private void updateHaves(Peer peer, int[] haves) {
        //Sending out any new haves that the file manager indicated
        for (int i = 0; i < haves.length; i++) {
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(haves[i]);
            peer.addOrder(new Pair<String, ArrayList<Object>>("have", arguments));
        }
        //Updates the frequency array if the peer has sent any haves
        while (true) {
            Integer idx = peer.getPeerHaves();
            if (idx == null) {
                break;
            }
            frequencyArray[idx] += 1;
        }
    }

    /**
     * Updates the file manager with any new pieces that have been
     * received from the peer.
     * @param peer
     */
    private void updateReceivedPieces(Peer peer) {
        while (true) {
            Request piece = peer.getNewPiece();
            if (piece != null) {
                tor.receivedPiece(piece);
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
     * @param peer
     */
    private void updatePeerPiece(Peer peer) {
        Request req = peer.getRequest(); //Get the latest request
        if (req != null) {
            //Check if I am willing to send a piece currently
            tor.fillOutPiece(req);
            if (req.block == null) {
                return;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(req);
            peer.addOrder(new Pair<String, ArrayList<Object>>("piece", arguments));
        }
    }
    
    /** 
     * Sends a new request order to a peer.
     * @param peer
     */
    private void updateRequests(Peer peer) {
        //Include some sort of timeout logic to check whether a request was not answered for too long.
        //Make sure to document the logic properly here to make sure all pieces can be received
        int requestCount = peer.getRequestCount();
        if (requestCount >= 10) {
            return;
        }
        int pieceLength = (int) tor.getPieceLength();
        int reqIndex = getRandomRequestIndex();
        int begin = requestedPieces.get(reqIndex);
        int length;
        boolean EOF = false;
        for (int i = 0; i < 10 - requestCount; i++) {
            if (begin + BLOCKSIZE >= pieceLength && reqIndex + 1 == frequencyArray.length) {
                length = pieceLength - begin;
                EOF = true;
            } else {
                length = BLOCKSIZE;
            }
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(reqIndex);
            arguments.add(begin);
            arguments.add(length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("request", arguments));
            begin += BLOCKSIZE;
            if (EOF) {
                requestedPieces.put(reqIndex, -1); //Mark the piece as fully requested
                reqIndex = getRandomRequestIndex();
                begin = requestedPieces.get(reqIndex);
            } else  if (begin >= pieceLength) {
                requestedPieces.put(reqIndex, -1);
                begin -= pieceLength;
                reqIndex += 1; //Make a check if this index is taken above, if yes use the above case to generate a random index
            }
        }
        //Add the requested index where left off and also update the docs if some details change.
    }
    
    /** 
     * Returns the index of a random available piece.
     * @return int
     */
    private int getRandomRequestIndex() {
        Random rand = new Random();
        while (true) {
            int reqIndex = rand.nextInt(availablePieceList.size());
            if (requestedPieces.containsKey(reqIndex)) {
                if (requestedPieces.get(reqIndex) != -1) {
                    return reqIndex;
                }
            } else {
                return reqIndex;
            }
        }
    }
    
    /** 
     * Proper explanation needed.
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
     * Proper explanation needed.
     * @param b
     */
    private void updateSortedArrayIndices(byte b) {

    }

    /**
     * The file manager has determined that the piece at index
     * was invalid and needs to be downloaded again.
     */
    public void redownloadPiece(int index) {

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